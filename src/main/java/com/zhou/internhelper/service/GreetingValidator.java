package com.zhou.internhelper.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话术事实校验器：拦截模型编造事实、篡改月数/天数、违反 AI 提及开关等输出。
 *
 * <p>无 Spring 依赖的纯校验组件，便于单元测试。校验失败时调用方回退到模板草稿。
 * 注意：技术词检查是「已知词典命中」式检查，无法拦截词典外自造词，
 * 属于降低幻觉概率的约束，而非强安全保证，需与低 temperature 和提示词约束配合。</p>
 */
public final class GreetingValidator {

    /** 实习月数：必须写成阿拉伯数字 + “个月” */
    private static final Pattern MONTH_OUT = Pattern.compile("(\\d+)\\s*个月");
    /** 每周出勤：支持「每周N天 / 每周出勤N天 / N天/周」 */
    private static final Pattern DAY_OUT = Pattern.compile(
            "(?:每周|每)\\s*(?:出勤)?\\s*(\\d+)\\s*天|(\\d+)\\s*天\\s*/\\s*周");

    private static final int ABS_MAX_MONTHS = 12;
    private static final int ABS_MAX_DAYS = 7;

    /** 别名：不同写法归一到同一词条，避免 SpringBoot / Spring Boot 误判 */
    private static final Map<String, String> ALIASES = Map.of(
            "k8s", "kubernetes",
            "kubernetes", "kubernetes",
            "springboot", "springboot",
            "springboot3", "springboot",
            "mysql", "mysql",
            "redis", "redis"
    );

    /** 内置兜底技术词；classpath 的 tech-whitelist.txt 存在时以其为准 */
    private static final Set<String> BUILTIN_TECH = Set.of(
            "java", "python", "c++", "c#", "go", "golang", "rust", "scala", "kotlin", "php", "nodejs",
            "springboot", "springcloud", "springmvc", "mybatis", "hibernate", "mysql", "postgresql",
            "oracle", "mongodb", "redis", "memcached", "elasticsearch", "kafka", "rabbitmq", "rocketmq",
            "zookeeper", "dubbo", "nacos", "kubernetes", "docker", "jenkins", "nginx", "linux", "git",
            "hadoop", "spark", "flink", "hive", "hbase", "flask", "django", "fastapi", "tensorflow",
            "pytorch", "vue", "react", "angular", "typescript", "javascript", "html", "css", "webpack",
            "android", "ios", "flutter", "unity", "aiagent", "llm", "rag", "prompt"
    );

    private final Set<String> techTerms;

    public GreetingValidator() {
        this(loadTechTerms());
    }

    /** 供测试注入自定义词典，避免依赖 classpath 资源 */
    public GreetingValidator(Set<String> techTerms) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String term : techTerms) {
            String n = normalize(term);
            if (!n.isEmpty()) {
                normalized.add(n);
            }
        }
        this.techTerms = Collections.unmodifiableSet(normalized);
    }

    public record Result(boolean ok, List<String> warnings) {
        public static Result pass(List<String> warnings) {
            return new Result(true, warnings == null ? List.of() : warnings);
        }

        public static Result fail(String reason) {
            return new Result(false, List.of(reason));
        }
    }

    /**
     * 校验一段话术。
     *
     * @param message 模型输出
     * @param v       GreetingService 构造的事实变量（school/schoolShort/degreeLabel/…）
     * @param months  裁剪后的实习月数
     * @param days    裁剪后的每周出勤天数
     */
    public Result validate(String message, Map<String, String> v,
                           int months, int days, int minLen, int maxLen) {
        if (message == null || message.isBlank()) {
            return Result.fail("模型输出为空");
        }
        String text = message.trim();
        List<String> warnings = new ArrayList<>();

        // 0) 字数：明显偏离直接失败，轻微超出仅警告
        int len = text.length();
        if (len < minLen * 0.8 || len > maxLen * 1.2) {
            return Result.fail("输出字数明显偏离目标区间：" + len + " 字");
        }
        if (len < minLen || len > maxLen) {
            warnings.add("字数略超目标区间：" + len + " 字");
        }

        Map<String, String> facts = v == null ? Map.of() : v;

        // 1) 学校 / 学历必须出现
        if (!containsAny(text, facts.get("school"), facts.get("schoolShort"))) {
            return Result.fail("输出中缺少学校信息");
        }
        if (!containsAny(text, facts.get("degreeLabel"))) {
            return Result.fail("输出中缺少学历信息");
        }

        // 2) 月数 / 天数：抽取所有表述，必须全部等于事实值且不超硬上限
        List<Integer> outMonths = extract(MONTH_OUT, text, 1);
        if (outMonths.isEmpty()) {
            return Result.fail("输出中未写明实习月数（需使用阿拉伯数字，如 3 个月）");
        }
        for (int m : outMonths) {
            if (m > ABS_MAX_MONTHS || m != months) {
                return Result.fail("输出中的实习月数与事实不一致：" + m + " 个月（应为 " + months + "）");
            }
        }

        List<Integer> outDays = extract(DAY_OUT, text, 1, 2);
        if (outDays.isEmpty()) {
            return Result.fail("输出中未写明每周出勤天数（需使用阿拉伯数字，如每周 4 天）");
        }
        for (int d : outDays) {
            if (d > ABS_MAX_DAYS || d != days) {
                return Result.fail("输出中的出勤天数与事实不一致：" + d + " 天（应为 " + days + "）");
            }
        }

        String normalizedText = normalize(text);

        // 3) AI 工具提及开关：明确关闭时不得在话术中出现（放在技能白名单之前，
        //    否则 aiagent 会先被技能白名单拦下，给出不准确的提示）
        if ("false".equalsIgnoreCase(facts.get("aiAgentOk"))
                && (normalizedText.contains("aiagent")
                    || text.contains("AI工具") || text.contains("AI 工具"))) {
            return Result.fail("个人资料设置为不提 AI 工具，但输出中出现了相关内容");
        }

        // 4) 技能白名单：输出中出现的技术词，必须能在个人资料侧找到依据
        //    明确排除 jdText / jobSkills（岗位要求不是候选人已具备的技能）
        String allowed = normalize(String.join(" ",
                nz(facts.get("profileSkills")),
                nz(facts.get("profileEvidence")),
                nz(facts.get("skillClause")),
                nz(facts.get("major")),
                nz(facts.get("grade")),
                nz(facts.get("schoolFull")),
                nz(facts.get("schoolShort")),
                nz(facts.get("degreeLabel")),
                nz(facts.get("startFrom")),
                nz(facts.get("company")),
                nz(facts.get("title")),
                nz(facts.get("aiPart"))));
        for (String term : techTerms) {
            String canonical = ALIASES.getOrDefault(term, term);
            // 词条本身与别名任一出现在输出中，就算命中
            boolean inText = normalizedText.contains(term)
                    || (!term.equals(canonical) && normalizedText.contains(canonical));
            if (!inText) {
                continue;
            }
            // 资料侧自带两种写法之一即可视为有依据
            boolean inAllowed = allowed.contains(term)
                    || (!term.equals(canonical) && allowed.contains(canonical));
            if (!inAllowed) {
                return Result.fail("输出包含个人资料中未提供的技能/技术词：" + term);
            }
        }

        return Result.pass(warnings);
    }

    /** 抽取正则中指定分组的所有数值 */
    private static List<Integer> extract(Pattern pattern, String text, int... groups) {
        List<Integer> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            for (int g : groups) {
                String raw = m.group(g);
                if (raw != null && !raw.isBlank()) {
                    try {
                        out.add(Integer.parseInt(raw));
                    } catch (NumberFormatException ignored) {
                        // 忽略异常数字，交由后续「未找到」分支统一拒绝
                    }
                }
            }
        }
        return out;
    }

    /** 归一化：小写、去除空白与常见分隔符，使 SpringBoot 与 Spring Boot 等价 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\-_·.]+", "");
    }

    private static boolean containsAny(String text, String... candidates) {
        if (text == null) {
            return false;
        }
        for (String c : candidates) {
            if (c != null && !c.isBlank() && text.contains(c.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Set<String> loadTechTerms() {
        Set<String> terms = new LinkedHashSet<>(BUILTIN_TECH);
        String path = "/tech-whitelist.txt";
        try (InputStream in = GreetingValidator.class.getResourceAsStream(path)) {
            if (in == null) {
                return terms;
            }
            Set<String> fromFile = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String cleaned = line.trim();
                    int comment = cleaned.indexOf('#');
                    if (comment >= 0) {
                        cleaned = cleaned.substring(0, comment).trim();
                    }
                    if (!cleaned.isEmpty()) {
                        // 一行可写多个词，用逗号或空格分隔
                        Arrays.stream(cleaned.split("[,\\s]+"))
                                .filter(t -> !t.isEmpty())
                                .forEach(fromFile::add);
                    }
                }
            }
            if (!fromFile.isEmpty()) {
                return fromFile;
            }
        } catch (Exception e) {
            // 读取失败时回退内置词典，不影响主流程
        }
        return terms;
    }
}
