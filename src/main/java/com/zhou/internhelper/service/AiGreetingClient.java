package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用封装。当前默认使用 DeepSeek，但不绑定厂商专有 SDK，只依赖通用 HTTP 接口。
 * 连接参数从 SettingsService 快照读取，网页保存后立即生效。
 */
@Service
public class AiGreetingClient {

    private static final Logger log = LoggerFactory.getLogger(AiGreetingClient.class);

    public static final String SYSTEM_PROMPT =
            "你是一位求职打招呼文案润色助手。只润色表达，不新增任何事实。" +
            "必须保持学校、学历、专业、技能句、实习月数、每周出勤天数、到岗时间与原文一致。" +
            "不要输出解释，只输出 JSON，格式为 {\"message\":\"润色后的话术\"}。";

    public static final String FREE_SYSTEM_PROMPT =
            "你是一位求职打招呼文案创作助手。根据提供的个人资料与岗位信息，直接写一段自然的打招呼话术。" +
            "只能使用资料里明确给出的事实，不得编造学校、学历、专业、技能、项目、实习月数或到岗时间。" +
            "话术中必须体现给定的实习月数与每周出勤天数，且不超过硬上限 12 个月、每周 7 天。" +
            "月数与天数必须用阿拉伯数字书写（例如 3 个月、每周 4 天），不要写成“三个月”等中文数字。" +
            "不要输出建议文案，不要输出解释，只输出 JSON，格式为 {\"message\":\"话术正文\"}。";

    /** 测试连接用的最小提示词，短小且固定，便于精确预估成本 */
    public static final String TEST_PROMPT = "ping";

    private final SettingsService settings;
    private final ObjectMapper objectMapper;

    public AiGreetingClient(SettingsService settings, ObjectMapper objectMapper) {
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    private SettingsService.SettingsSnapshot snap() {
        return settings.snapshot();
    }

    /** 构造用户提示词，供调用前估算成本使用 */
    public String buildUserPrompt(String draft, String style, Integer minLen, Integer maxLen) {
        int lo = Math.max(1, minLen == null ? 55 : minLen);
        int hi = Math.max(lo, maxLen == null ? 110 : maxLen);
        String styleText = "formal".equalsIgnoreCase(style) ? "正式" : "简洁";
        return "请在不新增事实的前提下润色下面这段求职打招呼话术。\n"
                + "风格：" + styleText + "\n"
                + "目标字数：" + lo + "-" + hi + " 字\n"
                + "要求：\n"
                + "1. 保持学校、学历、专业、技能句、实习月数、每周天数、到岗时间不变。\n"
                + "2. 只调整措辞和流畅度，不添加未提供的经历、项目或技能。\n"
                + "3. 实习月数与每周天数必须用阿拉伯数字书写（如 3 个月、每周 4 天），不要写成中文数字。\n"
                + "4. 只输出 JSON：{\"message\":\"润色后的话术\"}，不要输出解释。\n\n"
                + "草稿：\n" + nullToEmpty(draft);
    }

    /** 自由生成提示词：不使用模板草稿，直接把结构化资料交给 AI 创作 */
    public String buildFreeUserPrompt(Map<String, String> v, String style, Integer minLen, Integer maxLen) {
        int lo = Math.max(1, minLen == null ? 55 : minLen);
        int hi = Math.max(lo, maxLen == null ? 110 : maxLen);
        String styleText = "formal".equalsIgnoreCase(style) ? "正式" : "简洁自然";
        StringBuilder sb = new StringBuilder();
        sb.append("请写一段求职打招呼话术，直接面向招聘方，语气").append(styleText).append("。\n")
                .append("目标字数：").append(lo).append("-").append(hi).append(" 字。\n")
                .append("只能使用下面【个人资料】与【岗位信息】中的事实，不得编造任何经历、项目或技能。\n")
                .append("话术里必须自然体现【可实习月数】与【每周出勤天数】，不得改变这两个数字。\n")
                .append("月数与天数必须用阿拉伯数字书写（如 3 个月、每周 4 天），不要写成中文数字。\n")
                .append("不要写“建议”类内容，不要写解释，只输出 JSON：{\"message\":\"话术正文\"}。\n\n")
                .append("【个人资料】\n")
                .append("学校：").append(nz(v.get("schoolFull"))).append("\n")
                .append("年级：").append(nz(v.get("grade"))).append("\n")
                .append("学历：").append(nz(v.get("degree"))).append("生\n")
                .append("专业：").append(nz(v.get("major"))).append("\n")
                .append("技能标签：").append(nz(v.get("profileSkills"))).append("\n")
                .append("技能佐证：").append(nz(v.get("profileEvidence"))).append("\n")
                .append("匹配到的岗位方向：").append(nz(v.get("direction"))).append("\n")
                .append("方向技能句：").append(nz(v.get("skillClause"))).append("\n")
                .append("是否提及AI工具：").append("true".equals(v.get("aiAgentOk")) ? "可以提" : "不要提").append("\n")
                .append("可开始时间：").append(nz(v.get("startFrom"))).append("\n")
                .append("可实习月数：").append(nz(v.get("months"))).append(" 个月\n")
                .append("每周出勤天数：").append(nz(v.get("days"))).append(" 天\n\n")
                .append("【岗位信息】\n")
                .append("公司：").append(nz(v.get("company"))).append("\n")
                .append("岗位：").append(nz(v.get("title"))).append("\n")
                .append("技术栈：").append(nz(v.get("jobSkills"))).append("\n")
                .append("岗位要求：").append(nz(v.get("jdText"))).append("\n")
                .append("地点：").append(nz(v.get("rawLocation"))).append("\n");
        return sb.toString();
    }

    /** 自由生成：与 polish 相同的调用流程，但使用创作型 system prompt */
    public AiResult createFree(String prompt) {
        SettingsService.SettingsSnapshot s = snap();
        String baseUrl = s.baseUrl();
        String model = s.model();
        String apiKey = s.activeApiKey();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("模型接口地址、模型名或 API Key 未配置");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", FREE_SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("temperature", 0.4);
        body.put("max_tokens", s.maxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        body.put("thinking", Map.of("type", "disabled"));
        body.put("stream", false);

        String response = post(baseUrl, apiKey, body, s.timeoutMs());
        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("模型返回错误：" + root.path("error").toString());
            }
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("模型未返回话术内容");
            }
            String message = extractMessage(content);
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int cacheHitTokens = parseCacheHitTokens(usage, promptTokens);
            boolean usageReported = !usage.isMissingNode() && (promptTokens > 0 || completionTokens > 0);
            return new AiResult(message, promptTokens, completionTokens, cacheHitTokens, usageReported);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析自由生成响应失败：{}", e.getMessage());
            throw new IllegalStateException("解析自由生成响应失败", e);
        }
    }

    private String nz(String s) {
        return s == null || s.isBlank() ? "未提供" : s;
    }

    /** 调用模型并返回润色后的消息与 token 用量 */
    public AiResult polish(String prompt) {
        SettingsService.SettingsSnapshot s = snap();
        String baseUrl = s.baseUrl();
        String model = s.model();
        String apiKey = s.activeApiKey();
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("模型接口地址、模型名或 API Key 未配置");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", prompt)
        ));
        body.put("temperature", 0.2);
        body.put("max_tokens", s.maxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        // 话术润色不需要思维链；显式关闭可避免 reasoning 占满 max_tokens 导致 content 为空
        body.put("thinking", Map.of("type", "disabled"));
        body.put("stream", false);

        String response = post(baseUrl, apiKey, body, s.timeoutMs());

        try {
            JsonNode root = objectMapper.readTree(response);
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("模型返回错误：" + root.path("error").toString());
            }
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("模型未返回话术内容");
            }
            String message = extractMessage(content);
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            int cacheHitTokens = parseCacheHitTokens(usage, promptTokens);
            boolean usageReported = !usage.isMissingNode() && (promptTokens > 0 || completionTokens > 0);
            return new AiResult(message, promptTokens, completionTokens, cacheHitTokens, usageReported);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析模型响应失败：{}", e.getMessage());
            throw new IllegalStateException("解析模型响应失败", e);
        }
    }

    /**
     * 测试连接。使用传入的临时参数，不保存参数；请求本身会产生真实 token 用量，
     * 由调用方（SettingsController）按预检结果记账。返回用户可读结果与 token 用量。
     */
    public TestResult testConnection(String baseUrl, String model, String apiKey, Integer timeoutMs) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) {
            return TestResult.failure("接口地址、模型名或 API Key 未填写");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.trim());
        body.put("messages", List.of(Map.of("role", "user", "content", TEST_PROMPT)));
        body.put("max_tokens", 1);
        body.put("stream", false);

        int timeout = timeoutMs == null ? snap().timeoutMs() : timeoutMs;
        try {
            String response = post(baseUrl.trim(), apiKey.trim(), body, timeout);
            JsonNode root = objectMapper.readTree(response);
            if (root.hasNonNull("error")) {
                String msg = root.path("error").path("message").asText(root.path("error").toString());
                return TestResult.failure("模型返回错误：" + msg);
            }
            if (root.path("choices").isArray() && !root.path("choices").isEmpty()) {
                JsonNode usage = root.path("usage");
                int promptTokens = usage.path("prompt_tokens").asInt(0);
                int completionTokens = usage.path("completion_tokens").asInt(0);
                int cacheHitTokens = parseCacheHitTokens(usage, promptTokens);
                boolean usageReported = !usage.isMissingNode() && (promptTokens > 0 || completionTokens > 0);
                return new TestResult(true, "连接成功，模型可响应",
                        promptTokens, completionTokens, cacheHitTokens, usageReported);
            }
            return TestResult.failure("响应中未找到有效结果，请检查模型名");
        } catch (HttpClientErrorException.Unauthorized e) {
            return TestResult.failure("鉴权失败：API Key 不正确");
        } catch (HttpClientErrorException e) {
            return TestResult.failure("请求被拒绝：" + e.getStatusCode().value() + "，请检查余额与权限");
        } catch (HttpServerErrorException e) {
            return TestResult.failure("服务暂时不可用：" + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return TestResult.failure("连接失败：请检查网络或 Base URL");
        } catch (Exception e) {
            log.warn("测试连接失败：{}", e.getMessage());
            return TestResult.failure("测试失败：" + e.getMessage());
        }
    }

    private String post(String baseUrl, String apiKey, Map<String, Object> body, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);

        RestClient client = RestClient.builder().requestFactory(factory).build();
        String response = client.post()
                .uri(endpoint(baseUrl))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(String.class);
        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("模型返回空响应");
        }
        return response;
    }

    /** 兼容常见的缓存命中字段：prompt_tokens_details.cached_tokens / prompt_cache_hit_tokens / cached_tokens */
    private int parseCacheHitTokens(JsonNode usage, int promptTokens) {
        int cached = 0;
        JsonNode details = usage.path("prompt_tokens_details");
        if (details.isObject()) {
            cached = details.path("cached_tokens").asInt(0);
        }
        if (cached <= 0) {
            cached = usage.path("prompt_cache_hit_tokens").asInt(0);
        }
        if (cached <= 0) {
            cached = usage.path("cached_tokens").asInt(0);
        }
        if (cached < 0) {
            cached = 0;
        }
        return Math.min(cached, Math.max(promptTokens, 0));
    }

    private String endpoint(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }

    /** 兼容模型把 JSON 包在代码块或前后说明文字里的情况 */
    private String extractMessage(String content) throws Exception {
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        String json = start >= 0 && end > start ? text.substring(start, end + 1) : text;
        JsonNode node = objectMapper.readTree(json);
        String message = node.path("message").asText("");
        if (!StringUtils.hasText(message)) {
            throw new IllegalStateException("模型返回的 JSON 缺少 message 字段");
        }
        return message.trim();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public record AiResult(String message, int promptTokens, int completionTokens,
                           int cacheHitTokens, boolean usageReported) {
    }

    public record TestResult(boolean success, String message,
                             int promptTokens, int completionTokens,
                             int cacheHitTokens, boolean usageReported) {
        /** 失败结果：没有产生有效用量，token 计数为 0 */
        public static TestResult failure(String message) {
            return new TestResult(false, message, 0, 0, 0, false);
        }
    }
}
