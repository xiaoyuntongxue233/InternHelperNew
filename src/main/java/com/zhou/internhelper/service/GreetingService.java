package com.zhou.internhelper.service;

import com.zhou.internhelper.config.CandidateProperties;
import com.zhou.internhelper.entity.CandidateProfile;
import com.zhou.internhelper.entity.GreetingRule;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.CandidateProfileRepository;
import com.zhou.internhelper.repository.GreetingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话术生成器
 *
 * 输入：岗位（决定技能方向、岗位要求的时长/出勤）+ 个人资料（决定学校、专业、可接受上限、句式风格）
 * 规则：
 * 1. 技能句按岗位名/技术栈/要求原文关键词匹配（规则存在数据库里，按 priority 升序）
 * 2. 实习月数、每周天数 = 岗位要求 ∩ 个人能力，两条硬上限：≤6 个月、≤5 天
 * 3. 两套句式：concise（简洁）/ formal（正式）
 */
@Service
public class GreetingService {

    public static final String AI_PART = "，现可熟悉使用AI agent相关软件。";
    public static final String TAIL_TEMPLATE = "本人可连续实习至少%d个月，每周%d天。希望收到您的回复。";
    public static final String DEFAULT_CLAUSE = "熟悉Java，有Springboot，MySQL相关经验";

    private static final int ABS_MAX_MONTHS = 6;
    private static final int ABS_MAX_DAYS = 5;
    private static final int DEFAULT_MONTHS = 3;
    private static final int DEFAULT_DAYS = 5;
    private static final int MIN_MONTHS = 2;
    private static final int MIN_DAYS = 2;

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*天\\s*/\\s*周");
    private static final Pattern MONTHS_PATTERN = Pattern.compile("(\\d+)\\s*个月");

    private final GreetingRuleRepository ruleRepository;
    private final CandidateProfileRepository profileRepository;
    private final CandidateProperties candidate;

    /** 规则正则只编译一次，避免每条岗位重复编译 */
    private final Map<Long, Pattern> patternCache = new ConcurrentHashMap<>();

    public GreetingService(GreetingRuleRepository ruleRepository,
                           CandidateProfileRepository profileRepository,
                           CandidateProperties candidate) {
        this.ruleRepository = ruleRepository;
        this.profileRepository = profileRepository;
        this.candidate = candidate;
    }

    /** 生成结果：话术 + 匹配到的方向 + 时长 */
    public record Greeting(String message, String direction, int months, int days) {
    }

    /** 兼容旧调用：自动读库里的个人资料 */
    public Greeting generate(Job job) {
        return generate(job, null, null);
    }

    /**
     * @param profileOverride 传入则用它，不传则读库里的单行资料
     * @param styleOverride   传入则覆盖资料里的风格（concise / formal）
     */
    public Greeting generate(Job job, CandidateProfile profileOverride, String styleOverride) {
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
            Pattern pattern = patternCache.computeIfAbsent(rule.getId(),
                    id -> Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            if (pattern.matcher(blob).find()) {
                clause = rule.getSkillClause();
                direction = rule.getDirection() == null ? "未命名方向" : rule.getDirection();
                break;
            }
        }

        int jobMonths = parseMonths(blob);
        int jobDays = parseDays(blob);

        // 岗位要求 ∩ 个人能力；两条硬上限永远生效
        int months = Math.min(jobMonths, ABS_MAX_MONTHS);
        int days = Math.min(jobDays, ABS_MAX_DAYS);
        if (p != null) {
            months = Math.min(months, clamp(p.getMaxMonths(), 1, ABS_MAX_MONTHS));
            months = Math.max(months, clamp(p.getMinMonths(), 1, months));
            days = Math.min(days, clamp(p.getDaysPerWeek(), 1, ABS_MAX_DAYS));
        }

        String school = p != null && notBlank(p.getSchoolFull())
                ? p.getSchoolFull() : candidate.getSchool();
        String grade = p != null && notBlank(p.getGrade())
                ? p.getGrade() : candidate.getGrade();
        String major = p == null ? null : p.getMajor();
        boolean aiOk = p == null || !Boolean.FALSE.equals(p.getAiAgentOk());
        String startFrom = p == null ? null : p.getStartFrom();
        String style = styleOverride != null && !styleOverride.isBlank()
                ? styleOverride : (p == null || p.getStyle() == null ? "concise" : p.getStyle());

        String aiPart = aiOk ? AI_PART : "。";
        String message;

        if ("formal".equalsIgnoreCase(style)) {
            StringBuilder sb = new StringBuilder();
            sb.append("您好！我是").append(school).append(grade).append("本科生");
            if (notBlank(major)) sb.append("，").append(major).append("专业");
            sb.append("。本人").append(clause).append(aiPart);
            if (notBlank(startFrom)) {
                sb.append("目前可").append(startFrom).append("起到岗，可保证连续实习")
                        .append(months).append("个月、每周出勤").append(days).append("天。");
            } else {
                sb.append("目前可保证连续实习").append(months).append("个月、每周出勤")
                        .append(days).append("天。");
            }
            sb.append("期待您的回复，感谢！");
            message = sb.toString();
        } else {
            String head = "您好，我是" + school + grade + "本科生，本人";
            if (notBlank(startFrom)) {
                message = head + clause + aiPart
                        + String.format("本人可连续实习至少%d个月，每周%d天，%s即可到岗。希望收到您的回复。",
                        months, days, startFrom);
            } else {
                message = head + clause + aiPart + String.format(TAIL_TEMPLATE, months, days);
            }
        }

        return new Greeting(message, direction, months, days);
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

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
