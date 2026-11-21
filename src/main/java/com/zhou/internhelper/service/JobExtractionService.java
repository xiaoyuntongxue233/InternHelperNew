package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从页面文本抽取岗位字段的「草稿」服务。
 *
 * <p>安全边界：模型只负责从原文里挑字段，不负责产生事实。返回的每个非空字段都必须能在
 * （清洗后的）页面文本中逐字找到依据；找不到就留空并提示。接口不写库，用户核对后走原有保存流程。</p>
 */
@Service
public class JobExtractionService {

    private static final Logger log = LoggerFactory.getLogger(JobExtractionService.class);

    /** 输入文本上限：过长页面会让 token 成本失控 */
    public static final int MAX_PAGE_CHARS = 6000;
    public static final int MAX_SKILLS = 30;
    public static final int MAX_WARNINGS = 20;
    private static final int MAX_JD = 2000;
    private static final int MAX_SKILLS_CHARS = 512;
    private static final int MAX_COMPANY = 128;
    private static final int MAX_TITLE = 255;
    private static final int MAX_LOCATION = 128;
    private static final int MAX_SALARY_TEXT = 64;
    private static final String PROMPT_VERSION = "extract-v1";

    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|li|tr|h[1-6]|br)\\s*>");
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    /** 出勤天数必须落在「N天」上，不能把「每天工作8小时」的 8 当天数 */
    private static final Pattern WEEK_DAY = Pattern.compile("(\\d+)\\s*天");
    /** 纯网址输入：本功能不做服务端抓页面，明确拒绝，避免误调用模型与浪费额度 */
    private static final Pattern URL_ONLY = Pattern.compile("(?i)^https?://\\S+$");
    private static final Pattern SALARY_MONTHS = Pattern.compile("[·•/\\-]?\\d+薪");
    private static final Pattern RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)[^\\d]*-[^\\d]*(\\d+(?:\\.\\d+)?)");
    private static final Pattern SINGLE = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("company", "公司名"),
            Map.entry("title", "岗位名称"),
            Map.entry("rawLocation", "地点"),
            Map.entry("salaryText", "薪资文本"),
            Map.entry("monthsText", "实习时长"),
            Map.entry("daysText", "出勤天数"),
            Map.entry("jdText", "岗位要求"),
            Map.entry("skills", "技能标签")
    );

    private final ExtractionAiClient ai;
    private final AiQuotaService quota;
    private final MetaParser metaParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobExtractionService(ExtractionAiClient ai, AiQuotaService quota, MetaParser metaParser) {
        this.ai = ai;
        this.quota = quota;
        this.metaParser = metaParser;
    }

    /** 抽取草稿。字段全部可空：溯源失败的字段宁可留空，也不替用户猜。 */
    public record Draft(String company, String title, String rawLocation, String salaryText,
                        String salaryType, Integer salaryMin, Integer salaryMax,
                        Integer dailyMin, Integer dailyMax,
                        Integer months, Integer days, String jdText, String skills) {

        public static Draft empty() {
            return new Draft("", "", "", "", null, null, null, null, null, null, null, "", "");
        }
    }

    public record Result(Draft draft, List<String> warnings, boolean quotaExceeded) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("engine", "AI_EXTRACT");
            map.put("company", draft.company());
            map.put("title", draft.title());
            map.put("rawLocation", draft.rawLocation());
            map.put("salaryText", draft.salaryText());
            map.put("salaryType", draft.salaryType());
            map.put("salaryMin", draft.salaryMin());
            map.put("salaryMax", draft.salaryMax());
            map.put("dailyMin", draft.dailyMin());
            map.put("dailyMax", draft.dailyMax());
            map.put("months", draft.months());
            map.put("days", draft.days());
            map.put("jdText", draft.jdText());
            map.put("skills", draft.skills());
            map.put("warnings", warnings);
            map.put("quotaExceeded", quotaExceeded);
            return map;
        }
    }

    private record Salary(String type, Integer min, Integer max, Integer dailyMin, Integer dailyMax) {
        static Salary empty() {
            return new Salary(null, null, null, null, null);
        }
    }

    /** 清洗 HTML/空白，返回截断后的正文；同一份文本同时用于提示词和事实溯源 */
    public String clean(String pageText) {
        if (pageText == null) {
            return "";
        }
        String s;
        try {
            s = HtmlUtils.htmlUnescape(pageText);
        } catch (RuntimeException e) {
            s = pageText;
        }
        s = SCRIPT_STYLE.matcher(s).replaceAll(" ");
        s = BR_TAG.matcher(s).replaceAll(" ");
        s = BLOCK_END.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = SPACE.matcher(s).replaceAll(" ").trim();
        return s.length() <= MAX_PAGE_CHARS ? s : s.substring(0, MAX_PAGE_CHARS);
    }

    public Result extract(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            throw new IllegalArgumentException("请粘贴页面文本");
        }
        String cleaned = clean(pageText);
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("页面文本没有可识别内容，请检查粘贴的内容");
        }
        if (URL_ONLY.matcher(cleaned.trim()).matches()) {
            throw new IllegalArgumentException("请粘贴页面正文或 HTML，而不是网址；本功能不抓取网页。"
                    + "可先用抓取脚本取得页面文本，或把网址填入「网页链接」后手动补充字段。");
        }

        String prompt = ai.buildExtractUserPrompt(cleaned);
        BigDecimal estimate = quota.estimateCost(prompt);
        BigDecimal reserved = quota.reserve(estimate);
        if (reserved == null) {
            return new Result(Draft.empty(),
                    List.of("默认额度已用完，未能自动识别；请手动填写，或在设置页配置自己的 API Key。"), true);
        }

        try {
            AiGreetingClient.AiResult result = ai.extract(prompt);
            Result parsed = parseDraft(result.message(), cleaned);
            quota.charge(null, "extract", result.promptTokens(), result.cacheHitTokens(),
                    result.completionTokens(), result.usageReported(), estimate, PROMPT_VERSION);
            quota.release(reserved);
            return parsed;
        } catch (Exception e) {
            quota.release(reserved);
            log.warn("AI 岗位信息抽取失败：{}", e.getMessage());
            String message = safeMessage(e);
            if (message.contains("未配置")) {
                return new Result(Draft.empty(),
                        List.of("请先在设置页配置模型接口地址、模型名和 API Key。"), false);
            }
            return new Result(Draft.empty(), List.of("AI 识别失败：" + message), false);
        }
    }

    /** 四道闸门：字段白名单、事实溯源、数值 clamp、技能白名单 */
    private Result parseDraft(String json, String pageText) {
        List<String> warnings = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("模型返回的不是有效 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("模型返回的不是 JSON 对象");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!AiGreetingClient.EXTRACT_FIELDS.contains(name)) {
                warn(warnings, "已忽略模型返回的未知字段：" + name);
            }
        });

        String company = verifiedText(root, "company", pageText, warnings, MAX_COMPANY);
        String title = verifiedText(root, "title", pageText, warnings, MAX_TITLE);
        String rawLocation = verifiedText(root, "rawLocation", pageText, warnings, MAX_LOCATION);
        String salaryText = verifiedText(root, "salaryText", pageText, warnings, MAX_SALARY_TEXT);
        String jdText = verifiedText(root, "jdText", pageText, warnings, MAX_JD);
        String skills = verifiedSkills(root.get("skills"), pageText, warnings);
        Integer months = verifiedNumber(root.get("monthsText"), pageText, warnings, "monthsText", 2, 12);
        Integer days = verifiedNumber(root.get("daysText"), pageText, warnings, "daysText", 1, 7);
        Salary salary = parseSalary(salaryText, warnings);

        Draft draft = new Draft(company, title, rawLocation, salaryText,
                salary.type(), salary.min(), salary.max(), salary.dailyMin(), salary.dailyMax(),
                months, days, jdText, skills);
        return new Result(draft, List.copyOf(warnings), false);
    }

    /** 字符串字段：必须在页面原文中逐字出现，否则清空 */
    private static String verifiedText(JsonNode root, String field, String page,
                                       List<String> warnings, int max) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            warn(warnings, LABELS.get(field) + "格式不正确，已留空");
            return "";
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!containsEvidence(page, value)) {
            warn(warnings, LABELS.get(field) + "未在页面文本中找到，已留空，请手动核对");
            return "";
        }
        if (value.length() > max) {
            warn(warnings, LABELS.get(field) + "过长，已截断到 " + max + " 字");
            value = value.substring(0, max);
        }
        return value;
    }

    /** 数字字段：先做溯源（含数字的短语必须来自页面），再按硬边界收敛 */
    private static Integer verifiedNumber(JsonNode node, String page, List<String> warnings,
                                          String field, int min, int max) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            warn(warnings, LABELS.get(field) + "格式不正确，已留空");
            return null;
        }
        String value = node.asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!containsEvidence(page, value)) {
            warn(warnings, LABELS.get(field) + "未在页面文本中找到，已留空，请手动核对");
            return null;
        }
        String digitsSource = value;
        if ("daysText".equals(field)) {
            Matcher dayMatcher = WEEK_DAY.matcher(value);
            if (dayMatcher.find()) {
                digitsSource = dayMatcher.group(1);
            } else if (value.contains("小时") || value.contains("时")) {
                warn(warnings, "出勤天数来源疑似每日工时（" + value + "），已留空，请手动填写");
                return null;
            }
        }
        Matcher m = NUMBER.matcher(digitsSource);
        if (!m.find()) {
            warn(warnings, LABELS.get(field) + "里没有数字，请手动填写");
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            warn(warnings, LABELS.get(field) + "数字超出范围，已留空");
            return null;
        }
        int clamped = Math.max(min, Math.min(max, parsed));
        if (clamped != parsed) {
            warn(warnings, LABELS.get(field) + parsed + " 超出 " + min + "-" + max
                    + " 范围，已按 " + clamped + " 填入，请核对");
        }
        return clamped;
    }

    /** 技能白名单：只保留页面原文里真实出现过的技能项 */
    private static String verifiedSkills(JsonNode node, String page, List<String> warnings) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            warn(warnings, "技能标签格式不正确，已留空");
            return "";
        }
        String raw = node.asText().trim();
        if (raw.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；]")) {
            String skill = part.trim();
            if (skill.isEmpty()) {
                continue;
            }
            if (kept.size() >= MAX_SKILLS) {
                warn(warnings, "技能标签超过 " + MAX_SKILLS + " 项，已截断");
                break;
            }
            if (skill.length() > 64) {
                skill = skill.substring(0, 64);
            }
            if (!containsEvidence(page, skill)) {
                warn(warnings, "技能「" + skill + "」未在页面文本中找到，已丢弃");
                continue;
            }
            kept.add(skill);
        }
        StringBuilder sb = new StringBuilder();
        for (String skill : kept) {
            int extra = sb.length() == 0 ? 0 : 1;
            if (sb.length() + extra + skill.length() > MAX_SKILLS_CHARS) {
                warn(warnings, "技能标签过长，已截断");
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(skill);
        }
        return sb.toString();
    }

    /** 薪资：原文必须溯源；数值折算交给 MetaParser，单位为月薪时再反算回原始数值 */
    private Salary parseSalary(String salaryText, List<String> warnings) {
        if (salaryText.isEmpty()) {
            return Salary.empty();
        }
        int[] daily = metaParser.parseDailyRange(salaryText);
        if (daily == null) {
            warn(warnings, "薪资文本「" + salaryText + "」未能解析为数值，请手动填写薪资");
            return Salary.empty();
        }
        String s = normalizeForMatch(salaryText);
        if (s.contains("天") || s.contains("日")) {
            return new Salary("DAILY", daily[0], daily[1], daily[0], daily[1]);
        }
        int[] original = originalMonthly(salaryText);
        if (original != null) {
            return new Salary("MONTHLY", original[0], original[1], daily[0], daily[1]);
        }
        warn(warnings, "薪资单位无法识别，已按日薪填入，请核对");
        return new Salary("DAILY", daily[0], daily[1], daily[0], daily[1]);
    }

    /** 从月薪原文取原始数值（元），避免用日薪反算带来的四舍五入漂移 */
    private static int[] originalMonthly(String salaryText) {
        String s = Normalizer.normalize(salaryText, Normalizer.Form.NFKC)
                .replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        s = SALARY_MONTHS.matcher(s).replaceAll("");
        s = s.replace('~', '-').replace('～', '-').replace('—', '-').replace('－', '-');
        int factor;
        if (s.contains("万")) {
            factor = 10000;
        } else if (s.contains("k")) {
            factor = 1000;
        } else if (s.contains("月") || s.contains("元")) {
            factor = 1;
        } else {
            return null;
        }
        Matcher m = RANGE.matcher(s);
        if (m.find()) {
            return ordered(num(m.group(1)) * factor, num(m.group(2)) * factor);
        }
        m = SINGLE.matcher(s);
        if (m.find()) {
            int v = rounded(num(m.group(1)) * factor);
            return new int[]{v, v};
        }
        return null;
    }

    /** 忽略大小写/全半角/空白后做包含判断：值必须来自页面，才算有事实依据 */
    private static boolean containsEvidence(String page, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String p = normalizeForMatch(page);
        String v = normalizeForMatch(value);
        return !v.isEmpty() && p.contains(v);
    }

    private static String normalizeForMatch(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        n = n.replace('~', '-').replace('～', '-').replace('〜', '-')
                .replace('—', '-').replace('－', '-');
        return SPACE.matcher(n).replaceAll("");
    }

    private static void warn(List<String> warnings, String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(message);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("其余问题已省略，请自行核对。");
        }
    }

    private static double num(String s) {
        return Double.parseDouble(s);
    }

    private static int rounded(double v) {
        return (int) Math.round(v);
    }

    private static int[] ordered(double a, double b) {
        return a <= b ? new int[]{rounded(a), rounded(b)} : new int[]{rounded(b), rounded(a)};
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
