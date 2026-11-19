package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.internhelper.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型调用封装。当前默认使用 DeepSeek，但不绑定厂商专有 SDK，只依赖通用 HTTP 接口。
 */
@Service
public class AiGreetingClient {

    private static final Logger log = LoggerFactory.getLogger(AiGreetingClient.class);

    public static final String SYSTEM_PROMPT =
            "你是一位求职打招呼文案润色助手。只润色表达，不新增任何事实。" +
            "必须保持学校、学历、专业、技能句、实习月数、每周出勤天数、到岗时间与原文一致。" +
            "不要输出解释，只输出 JSON，格式为 {\"message\":\"润色后的话术\"}。";

    private final AiProperties ai;
    private final AiQuotaService quota;
    private final ObjectMapper objectMapper;

    public AiGreetingClient(AiProperties ai, AiQuotaService quota, ObjectMapper objectMapper) {
        this.ai = ai;
        this.quota = quota;
        this.objectMapper = objectMapper;
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
                + "3. 只输出 JSON：{\"message\":\"润色后的话术\"}，不要输出解释。\n\n"
                + "草稿：\n" + nullToEmpty(draft);
    }

    /** 调用模型并返回润色后的消息与 token 用量 */
    public AiResult polish(String prompt) {
        String baseUrl = ai.getBaseUrl();
        String model = ai.getModel();
        String apiKey = quota.activeApiKey();
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
        body.put("max_tokens", ai.getMaxTokens());
        body.put("response_format", Map.of("type", "json_object"));
        // 话术润色不需要思维链；显式关闭可避免 reasoning 占满 max_tokens 导致 content 为空
        body.put("thinking", Map.of("type", "disabled"));
        body.put("stream", false);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ai.getTimeoutMs());
        factory.setReadTimeout(ai.getTimeoutMs());

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
}
