package com.zhou.internhelper.service;

import com.zhou.internhelper.config.CandidateProperties;
import com.zhou.internhelper.config.GreetingProperties;
import com.zhou.internhelper.entity.CandidateProfile;
import com.zhou.internhelper.service.SettingsService.SettingsSnapshot;
import com.zhou.internhelper.entity.GreetingRule;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.CandidateProfileRepository;
import com.zhou.internhelper.repository.GreetingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话术生成器。
 *
 * RULE：按内置模板确定性渲染。
 * AI / HYBRID：先渲染模板草稿，再交给模型润色；模型失败、超时、校验不通过或额度不足时回退到草稿。
 */
@Service
public class GreetingService {

    private static final Logger log = LoggerFactory.getLogger(GreetingService.class);

    public static final String ADVICE = "实习最好不超过6个月，每周最好不超过6天。";
    public static final String AI_PART = "，也熟悉AI agent相关工具。";
    public static final String DEFAULT_CLAUSE = "熟悉Java，有SpringBoot、MySQL相关经验";

    /** 硬上限：可以超过建议值，但后端强制裁剪 */
    public static final int ABS_MAX_MONTHS = 12;
    public static final int ABS_MAX_DAYS = 7;

    /** 建议值：只展示，不写入对外话术 */
    public static final int SOFT_MAX_MONTHS = 6;
    public static final int SOFT_MAX_DAYS = 6;

    private static final int DEFAULT_MONTHS = 3;
    private static final int DEFAULT_DAYS = 5;
    private static final int MIN_MONTHS = 2;
    private static final int MIN_DAYS = 2;

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*天\\s*/\\s*周");
    private static final Pattern MONTHS_PATTERN = Pattern.compile("(\\d+)\\s*个月");

    private final GreetingRuleRepository ruleRepository;
    private final CandidateProfileRepository profileRepository;
    private final CandidateProperties candidate;
    private final GreetingTemplateService templateService;
    private final AiGreetingClient aiClient;
    private final AiQuotaService quota;
    private final GreetingProperties greetingProperties;
    private final SettingsService settings;
    /** 事实校验器：词典在构造时加载一次，重启生效 */
    private final GreetingValidator validator = new GreetingValidator();

    /** 规则正则缓存：key 为规则 id，value 含原始正则；数据库里的正则变了会自动失效 */
    private final ConcurrentHashMap<Long, CachedPattern> patternCache = new ConcurrentHashMap<>();

    public GreetingService(GreetingRuleRepository ruleRepository,
                           CandidateProfileRepository profileRepository,
                           CandidateProperties candidate,
                           GreetingTemplateService templateService,
                           AiGreetingClient aiClient,
                           AiQuotaService quota,
                           GreetingProperties greetingProperties,
                           SettingsService settings) {
        this.ruleRepository = ruleRepository;
        this.profileRepository = profileRepository;
        this.candidate = candidate;
        this.templateService = templateService;
        this.aiClient = aiClient;
        this.quota = quota;
        this.greetingProperties = greetingProperties;
        this.settings = settings;
    }

    public record Greeting(String message, String direction, int months, int days,
                           String advice, String engine, String templateId,
                           List<String> warnings, boolean quotaExceeded) {
        public Greeting(String message, String direction, int months, int days) {
            this(message, direction, months, days, ADVICE, "RULE", null, List.of(), false);
        }
    }

    private record Facts(String style, String direction, int months, int days,
                         int minLen, int maxLen, Map<String, String> variables) {
    }

    private record Validation(boolean ok, List<String> warnings) {
    }

    private record CachedPattern(String source, Pattern pattern) {
    }

    /** 兼容旧调用：自动读库里的个人资料 */
    public Greeting generate(Job job) {
        return generate(job, null, null);
    }

    public Greeting generate(Job job, CandidateProfile profileOverride, String styleOverride) {
        SettingsSnapshot s = settings.snapshot();
        String template = s.template() == null ? "concise" : s.template();
        boolean free = "free".equalsIgnoreCase(template);
        Facts facts = buildFacts(job, profileOverride, styleOverride, template);
        GreetingTemplateService.Rendered rendered = templateService.render(facts.style(), facts.variables());
        String draft = rendered.text();

        String engine = s.engine() == null ? "RULE" : s.engine();
        boolean aiEnabled = "AI".equalsIgnoreCase(engine) || "HYBRID".equalsIgnoreCase(engine);
        if (!aiEnabled) {
            if (free) {
                List<String> warn = List.of("已选择 AI自由生成，但当前是 RULE 模式；已用简洁模板兜底，请切换到 AI / HYBRID。");
                return new Greeting(draft, facts.direction(), facts.months(), facts.days(),
                        ADVICE, "RULE", "free", warn, false);
            }
            return new Greeting(draft, facts.direction(), facts.months(), facts.days(),
                    ADVICE, "RULE", rendered.templateId(), List.of(), false);
        }
        if (free) {
            return generateFreeWithAi(job, facts, draft, s);
        }
        return generateWithAi(job, facts, rendered.templateId(), draft, s);
    }

    /**
     * AI 自由生成（新架构）：模型只设计「表达骨架」，不接触任何真实事实值。
     * 流程：骨架提示词（无事实） → 模型输出带占位符的骨架 → 结构校验 → 后端确定性回填事实 → 事实校验。
     * 攻击面从源头收敛：模型没有机会写出某个具体学校/技能/数字；词典与事实校验保留为最后一道网。
     */
    private Greeting generateFreeWithAi(Job job, Facts facts, String fallbackDraft, SettingsSnapshot s) {
        List<String> warnings = new ArrayList<>();
        String prompt = aiClient.buildSkeletonUserPrompt(facts.style(), facts.minLen(), facts.maxLen());
        BigDecimal estimate = quota.estimateCost(prompt);

        // 预检 + 并发预留；返回 null 表示拒绝调用
        BigDecimal reserved = quota.reserve(estimate);
        if (reserved == null) {
            warnings.add("默认额度已用完，已使用内置模板生成；如需更多生成，请自行接入 API。");
            return finishAi(fallbackDraft, facts, "free", warnings, true, s);
        }

        try {
            AiGreetingClient.AiResult result = aiClient.createFree(prompt);
            // 第一道闸门：骨架里只能是白名单占位符 + 连接词
            GreetingValidator.Result skeletonCheck = validator.validateSkeleton(
                    result.message(), AiGreetingClient.SKELETON_SLOTS, AiGreetingClient.SKELETON_REQUIRED_SLOTS);
            if (!skeletonCheck.ok()) {
                quota.release(reserved);
                warnings.addAll(skeletonCheck.warnings());
                warnings.add("模型输出未通过骨架校验。");
                return finishAi(fallbackDraft, facts, "free", warnings, false, s);
            }
            // 后端回填：所有事实来自确定性变量，模型无权决定具体值
            String filled = templateService.renderText(result.message(), facts.variables());
            // 第二道闸门：回填后仍跑一次事实校验（词典兜底），确保连接词没有夹带未提供的技术词
            Validation validation = validate(filled, facts);
            warnings.addAll(validation.warnings());
            if (!validation.ok()) {
                quota.release(reserved);
                warnings.add("模型输出未通过校验。");
                return finishAi(fallbackDraft, facts, "free", warnings, false, s);
            }
            quota.charge(job.getId(), "free", result.promptTokens(), result.cacheHitTokens(),
                    result.completionTokens(), result.usageReported(), estimate, greetingProperties.getPromptVersion());
            quota.release(reserved);
            return new Greeting(filled, facts.direction(), facts.months(), facts.days(),
                    ADVICE, "AI", "free", warnings, false);
        } catch (Exception e) {
            quota.release(reserved);
            log.warn("AI 自由生成失败，回退模板：{}", e.getMessage());
            warnings.add("AI 自由生成失败：" + e.getMessage());
            return finishAi(fallbackDraft, facts, "free", warnings, false, s);
        }
    }

    private Greeting generateWithAi(Job job, Facts facts, String templateId, String draft,
                                    SettingsSnapshot s) {
        List<String> warnings = new ArrayList<>();
        String prompt = aiClient.buildUserPrompt(draft, facts.style(), facts.minLen(), facts.maxLen());
        BigDecimal estimate = quota.estimateCost(prompt);

        // 预检 + 并发预留；返回 null 表示拒绝调用
        BigDecimal reserved = quota.reserve(estimate);
        if (reserved == null) {
            warnings.add("默认额度已用完，已使用内置模板生成；如需更多生成，请自行接入 API。");
            return finishAi(draft, facts, templateId, warnings, true, s);
        }

        try {
            AiGreetingClient.AiResult result = aiClient.polish(prompt);
            Validation validation = validate(result.message(), facts);
            warnings.addAll(validation.warnings());
            if (!validation.ok()) {
                quota.release(reserved);
                warnings.add("模型输出未通过校验。");
                return finishAi(draft, facts, templateId, warnings, false, s);
            }
            quota.charge(job.getId(), templateId, result.promptTokens(), result.cacheHitTokens(),
                    result.completionTokens(), result.usageReported(), estimate, greetingProperties.getPromptVersion());
            quota.release(reserved);
            return new Greeting(result.message(), facts.direction(), facts.months(), facts.days(),
                    ADVICE, "AI", templateId, warnings, false);
        } catch (Exception e) {
            quota.release(reserved);
            log.warn("AI 话术生成失败：{}", e.getMessage());
            warnings.add("AI 生成失败：" + e.getMessage());
            return finishAi(draft, facts, templateId, warnings, false, s);
        }
    }

    /** 失败或额度不足时：按设置决定是否回退模板 */
    private Greeting finishAi(String draft, Facts facts, String templateId,
                              List<String> warnings, boolean quotaExceeded, SettingsSnapshot s) {
        if (s.autoFallback()) {
            warnings.add("已使用内置模板生成。");
            return new Greeting(draft, facts.direction(), facts.months(), facts.days(),
                    ADVICE, "AI", templateId, warnings, quotaExceeded);
        }
        return new Greeting("", facts.direction(), facts.months(), facts.days(),
                ADVICE, "AI", templateId, warnings, quotaExceeded);
    }

    /** 委托给独立校验器：数值精确匹配 + 技能白名单 + AI 提及开关 + 字数/学校/学历 */
    private Validation validate(String message, Facts facts) {
        GreetingValidator.Result r = validator.validate(message, facts.variables(),
                facts.months(), facts.days(), facts.minLen(), facts.maxLen());
        return new Validation(r.ok(), r.warnings());
    }

    private Facts buildFacts(Job job, CandidateProfile profileOverride, String styleOverride,
                             String defaultTemplate) {
        CandidateProfile p = profileOverride != null
                ? profileOverride
                : profileRepository.findById(1L).orElse(null);

        String blob = String.join(" ",
                nullToEmpty(job.getTitle()),
                nullToEmpty(job.getSkills()),
                nullToEmpty(job.getJdText()));

        String clause = DEFAULT_CLAUSE;
        String direction = "通用 Java 方向";
        for (GreetingRule rule : ruleRepository.findByEnabledTrueOrderByPriorityAsc()) {
            Pattern pattern;
            try {
                pattern = cachedPattern(rule);
            } catch (Exception e) {
                log.warn("忽略非法话术规则 {}: {}", rule.getId(), e.getMessage());
                continue;
            }
            if (pattern.matcher(blob).find()) {
                clause = rule.getSkillClause() == null ? DEFAULT_CLAUSE : rule.getSkillClause();
                direction = rule.getDirection() == null ? "未命名方向" : rule.getDirection();
                break;
            }
        }

        String evidence = matchEvidence(p, blob + " " + nullToEmpty(p == null ? null : p.getSkills()));
        if (StringUtils.hasText(evidence)) {
            clause = clause + "，" + evidence;
        }

        int jobMonths = parseMonths(blob);
        int jobDays = parseDays(blob);
        int months = Math.min(jobMonths, ABS_MAX_MONTHS);
        int days = Math.min(jobDays, ABS_MAX_DAYS);
        if (p != null) {
            months = Math.min(months, clamp(p.getMaxMonths(), 1, ABS_MAX_MONTHS));
            months = Math.max(months, clamp(p.getMinMonths(), 1, ABS_MAX_MONTHS));
            months = Math.min(months, ABS_MAX_MONTHS);
            days = Math.min(days, clamp(p.getDaysPerWeek(), 1, ABS_MAX_DAYS));
        }

        String school = firstNotBlank(
                p == null ? null : p.getSchoolFull(),
                p == null ? null : p.getSchoolShort(),
                candidate.getSchool());
        String grade = firstNotBlank(p == null ? null : p.getGrade(), candidate.getGrade());
        String degreeLabel = degreeLabel(p == null ? null : p.getDegree());
        String major = p == null ? null : p.getMajor();
        String majorClause = StringUtils.hasText(major) ? "，" + major.trim() + "专业" : "";
        String nameClause = p != null && StringUtils.hasText(p.getName()) ? "，" + p.getName().trim() : "";
        boolean aiOk = p == null || !Boolean.FALSE.equals(p.getAiAgentOk());
        String aiPart = aiOk ? AI_PART : "。";
        String startFrom = p == null ? null : p.getStartFrom();
        String startFromClause = StringUtils.hasText(startFrom) ? "，" + startFrom.trim() + "即可到岗" : "";
        String style = StringUtils.hasText(styleOverride)
                ? styleOverride
                : (p == null || !StringUtils.hasText(p.getStyle())
                    ? ("concise".equalsIgnoreCase(defaultTemplate) || "formal".equalsIgnoreCase(defaultTemplate)
                        ? defaultTemplate : "concise")
                    : p.getStyle());

        int minLen = clamp(p == null ? 55 : p.getMinLen(), 20, 300);
        int maxLen = clamp(p == null ? 110 : p.getMaxLen(), minLen, 300);

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("school", school);
        variables.put("schoolShort", firstNotBlank(p == null ? null : p.getSchoolShort(), school));
        variables.put("grade", grade);
        variables.put("degreeLabel", degreeLabel);
        variables.put("major", major == null ? "" : major.trim());
        variables.put("majorClause", majorClause);
        variables.put("nameClause", nameClause);
        variables.put("skillClause", clause);
        variables.put("direction", direction);
        variables.put("months", String.valueOf(months));
        variables.put("days", String.valueOf(days));
        variables.put("startFromClause", startFromClause);
        variables.put("aiPart", aiPart);
        variables.put("company", nullToEmpty(job.getCompany()));
        variables.put("title", nullToEmpty(job.getTitle()));

        // 自由生成模式使用的原始资料
        variables.put("schoolFull", p == null ? school : firstNotBlank(p.getSchoolFull(), school));
        variables.put("degree", p == null ? "本科" : firstNotBlank(p.getDegree(), "本科"));
        variables.put("profileSkills", p == null ? "" : nullToEmpty(p.getSkills()));
        variables.put("profileEvidence", p == null ? "" : nullToEmpty(p.getEvidence()));
        variables.put("startFrom", startFrom == null ? "" : startFrom.trim());
        variables.put("aiAgentOk", aiOk ? "true" : "false");
        variables.put("jobSkills", nullToEmpty(job.getSkills()));
        variables.put("jdText", truncate(nullToEmpty(job.getJdText()), 1000));
        variables.put("rawLocation", firstNotBlank(job.getRawLocation(),
                String.join("", job.getProvince() == null ? "" : job.getProvince(),
                        job.getCity() == null ? "" : job.getCity(),
                        job.getDistrict() == null ? "" : job.getDistrict())));

        return new Facts(style, direction, months, days, minLen, maxLen, variables);
    }

    private String matchEvidence(CandidateProfile p, String blob) {
        if (p == null || !StringUtils.hasText(p.getEvidence())) {
            return "";
        }
        String lower = blob.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String part : p.getEvidence().split("[;；]")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = part.substring(0, idx).trim();
            String sentence = part.substring(idx + 1).trim();
            if (!key.isEmpty() && lower.contains(key.toLowerCase())) {
                if (sb.length() > 0) {
                    sb.append("，");
                }
                sb.append(sentence);
            }
        }
        return sb.toString();
    }

    private Pattern cachedPattern(GreetingRule rule) {
        String source = rule.getPattern() == null ? "" : rule.getPattern();
        CachedPattern cached = patternCache.compute(rule.getId(), (id, old) -> {
            if (old != null && old.source().equals(source)) {
                return old;
            }
            return new CachedPattern(source, Pattern.compile(source, Pattern.CASE_INSENSITIVE));
        });
        return cached.pattern();
    }

    private int parseDays(String text) {
        Matcher m = DAYS_PATTERN.matcher(text);
        if (m.find()) {
            return clamp(Integer.parseInt(m.group(1)), MIN_DAYS, ABS_MAX_DAYS, DEFAULT_DAYS);
        }
        return DEFAULT_DAYS;
    }

    private int parseMonths(String text) {
        Matcher m = MONTHS_PATTERN.matcher(text);
        if (m.find()) {
            return clamp(Integer.parseInt(m.group(1)), MIN_MONTHS, ABS_MAX_MONTHS, DEFAULT_MONTHS);
        }
        return DEFAULT_MONTHS;
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        if (value == null || value < 1) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(Integer value, int min, int max) {
        int x = value == null ? min : value;
        if (x < min) x = min;
        if (x > max) x = max;
        return x;
    }

    private String degreeLabel(String degree) {
        String d = StringUtils.hasText(degree) ? degree.trim() : "本科";
        return d.endsWith("生") ? d : d + "生";
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "";
    }


    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
