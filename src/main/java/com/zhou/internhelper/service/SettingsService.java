package com.zhou.internhelper.service;

import com.zhou.internhelper.config.AiProperties;
import com.zhou.internhelper.config.BillingProperties;
import com.zhou.internhelper.config.GreetingProperties;
import com.zhou.internhelper.entity.AppSetting;
import com.zhou.internhelper.repository.AppSettingRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置。环境变量作为首次启动的种子，之后以数据库值为准。
 * 快照不可变，保存后整体替换，避免读到半更新状态。
 */
@Service
public class SettingsService {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AppSettingRepository repository;
    private final AiProperties ai;
    private final BillingProperties billing;
    private final GreetingProperties greeting;

    private volatile SettingsSnapshot snapshot;

    public SettingsService(AppSettingRepository repository, AiProperties ai,
                           BillingProperties billing, GreetingProperties greeting) {
        this.repository = repository;
        this.ai = ai;
        this.billing = billing;
        this.greeting = greeting;
    }

    @PostConstruct
    @Transactional
    public void init() {
        seed("greeting.engine", greeting.getEngine());
        seed("greeting.template", "concise");
        seed("greeting.auto-fallback", "true");
        seed("ai.base-url", ai.getBaseUrl());
        seed("ai.model", ai.getModel());
        seed("ai.key-source", StringUtils.hasText(ai.getByoApiKey())
                || "BYO".equalsIgnoreCase(billing.getMode()) ? "BYO" : "PLATFORM");
        seed("ai.byo-api-key", ai.getByoApiKey());
        seed("ai.price-input-per-1m", str(ai.getPriceInputPer1m()));
        seed("ai.price-input-cache-hit-per-1m", str(ai.getPriceInputCacheHitPer1m()));
        seed("ai.price-input-cache-miss-per-1m", str(ai.getPriceInputCacheMissPer1m()));
        seed("ai.price-output-per-1m", str(ai.getPriceOutputPer1m()));
        seed("ai.timeout-ms", String.valueOf(ai.getTimeoutMs()));
        seed("ai.max-tokens", String.valueOf(ai.getMaxTokens()));
        refresh();
    }

    private void seed(String key, String value) {
        if (!repository.existsById(key)) {
            repository.save(new AppSetting(key, value == null ? "" : value));
        }
    }

    public SettingsSnapshot snapshot() {
        return snapshot;
    }

    /** 前端短名 → 数据库 key。前端与全名都接受，避免两边不同步 */
    private static final Map<String, String> KEY_ALIAS = Map.ofEntries(
            Map.entry("engine", "greeting.engine"),
            Map.entry("template", "greeting.template"),
            Map.entry("autoFallback", "greeting.auto-fallback"),
            Map.entry("keySource", "ai.key-source"),
            Map.entry("baseUrl", "ai.base-url"),
            Map.entry("model", "ai.model"),
            Map.entry("timeoutMs", "ai.timeout-ms"),
            Map.entry("maxTokens", "ai.max-tokens"),
            Map.entry("priceInputPer1m", "ai.price-input-per-1m"),
            Map.entry("priceInputCacheHitPer1m", "ai.price-input-cache-hit-per-1m"),
            Map.entry("priceInputCacheMissPer1m", "ai.price-input-cache-miss-per-1m"),
            Map.entry("priceOutputPer1m", "ai.price-output-per-1m"),
            Map.entry("apiKey", "ai.byo-api-key")
    );

    private String canonicalKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("设置项名称不能为空");
        }
        String trimmed = key.trim();
        return KEY_ALIAS.getOrDefault(trimmed, trimmed);
    }

    @Transactional
    public SettingsSnapshot update(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("没有需要保存的设置");
        }
        patch.forEach((rawKey, value) -> {
            String key = canonicalKey(rawKey);
            AppSetting row = repository.findById(key)
                    .orElseGet(() -> new AppSetting(key, null));
            row.setSettingValue(value == null ? "" : String.valueOf(value));
            repository.save(row);
        });
        refresh();
        return snapshot;
    }

    private void refresh() {
        Map<String, String> values = new LinkedHashMap<>();
        List<AppSetting> rows = repository.findAll();
        rows.forEach(r -> values.put(r.getSettingKey(), r.getSettingValue()));

        String engine = normalizeEngine(values.get("greeting.engine"));
        String template = normalizeTemplate(values.get("greeting.template"));
        boolean autoFallback = !"false".equalsIgnoreCase(values.get("greeting.auto-fallback"));
        String baseUrl = trimToNull(values.get("ai.base-url"));
        String model = trimToNull(values.get("ai.model"));
        String keySource = "BYO".equalsIgnoreCase(values.get("ai.key-source")) ? "BYO" : "PLATFORM";
        String byoApiKey = trimToNull(values.get("ai.byo-api-key"));

        BigDecimal input = toPerMillion(values.get("ai.price-input-per-1m"), ai.getPriceInputPer1k());
        BigDecimal hit = toPerMillion(values.get("ai.price-input-cache-hit-per-1m"), ai.getPriceInputCacheHitPer1k());
        BigDecimal miss = toPerMillion(values.get("ai.price-input-cache-miss-per-1m"), ai.getPriceInputCacheMissPer1k());
        BigDecimal output = toPerMillion(values.get("ai.price-output-per-1m"), ai.getPriceOutputPer1k());

        int timeoutMs = parseInt(values.get("ai.timeout-ms"), ai.getTimeoutMs(), 1000, 120000);
        int maxTokens = parseInt(values.get("ai.max-tokens"), ai.getMaxTokens(), 50, 4000);

        snapshot = new SettingsSnapshot(engine, template, autoFallback, baseUrl, model,
                keySource, byoApiKey, nullToEmpty(ai.getPlatformApiKey()),
                input, hit, miss, output, timeoutMs, maxTokens,
                billing.getDefaultBudgetCny());
    }

    /** 公开给controller用于校验，不落库 */
    public void validatePatch(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("没有需要保存的设置");
        }
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = canonicalKey(e.getKey());
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            switch (key) {
                case "greeting.engine" -> {
                    if (!List.of("RULE", "AI", "HYBRID").contains(value.toUpperCase())) {
                        throw new IllegalArgumentException("生成模式只能是 RULE / AI / HYBRID");
                    }
                }
                case "greeting.template" -> {
                    if (!List.of("concise", "formal", "free").contains(value.toLowerCase())) {
                        throw new IllegalArgumentException("模板只能是 concise / formal / free");
                    }
                }
                case "greeting.auto-fallback" -> {
                    if (!List.of("true", "false").contains(value.toLowerCase())) {
                        throw new IllegalArgumentException("自动回退只能是 true / false");
                    }
                }
                case "ai.key-source" -> {
                    if (!List.of("PLATFORM", "BYO").contains(value.toUpperCase())) {
                        throw new IllegalArgumentException("Key 来源只能是 PLATFORM / BYO");
                    }
                }
                case "ai.base-url" -> {
                    if (StringUtils.hasText(value)) {
                        try {
                            URI uri = URI.create(value);
                            String scheme = uri.getScheme();
                            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                                throw new IllegalArgumentException();
                            }
                        } catch (Exception ex) {
                            throw new IllegalArgumentException("Base URL 必须是 http 或 https 地址");
                        }
                    }
                }
                case "ai.model" -> {
                    if (value.length() > 128) {
                        throw new IllegalArgumentException("模型名过长");
                    }
                }
                case "ai.byo-api-key" -> {
                    if (value.length() > 512) {
                        throw new IllegalArgumentException("API Key 过长");
                    }
                }
                case "ai.timeout-ms" -> parseInt(value, ai.getTimeoutMs(), 1000, 120000);
                case "ai.max-tokens" -> parseInt(value, ai.getMaxTokens(), 50, 4000);
                case "ai.price-input-per-1m", "ai.price-input-cache-hit-per-1m",
                     "ai.price-input-cache-miss-per-1m", "ai.price-output-per-1m" -> {
                    BigDecimal bd = parseDecimal(value);
                    if (bd.signum() < 0) {
                        throw new IllegalArgumentException("单价不能为负数");
                    }
                }
                default -> throw new IllegalArgumentException("未知设置项：" + key);
            }
        }
    }

    /** 脱敏展示：保留前4后4，其余用星号 */
    public String maskKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String s = key.trim();
        if (s.length() <= 8) {
            return "****";
        }
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private BigDecimal toPerMillion(String per1m, BigDecimal legacyPer1k) {
        BigDecimal v = parseDecimal(per1m);
        if (v.signum() > 0) {
            return v;
        }
        return legacyPer1k == null ? BigDecimal.ZERO : legacyPer1k.multiply(THOUSAND);
    }

    private BigDecimal parseDecimal(String value) {
        if (!StringUtils.hasText(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private int parseInt(String value, int fallback, int min, int max) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String normalizeEngine(String value) {
        return "AI".equalsIgnoreCase(value) || "HYBRID".equalsIgnoreCase(value)
                ? value.toUpperCase() : "RULE";
    }

    private String normalizeTemplate(String value) {
        if ("formal".equalsIgnoreCase(value)) return "formal";
        if ("free".equalsIgnoreCase(value)) return "free";
        return "concise";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String str(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    /** 不可变配置快照 */
    public record SettingsSnapshot(String engine, String template, boolean autoFallback,
                                   String baseUrl, String model, String keySource,
                                   String byoApiKey, String platformApiKey,
                                   BigDecimal inputPricePer1m, BigDecimal hitPricePer1m,
                                   BigDecimal missPricePer1m, BigDecimal outputPricePer1m,
                                   int timeoutMs, int maxTokens, BigDecimal budgetCny) {

        public boolean useByo() {
            return "BYO".equalsIgnoreCase(keySource) && StringUtils.hasText(byoApiKey);
        }

        public String activeApiKey() {
            return useByo() ? byoApiKey : platformApiKey;
        }

        public BigDecimal effectiveMissPrice() {
            return missPricePer1m.signum() > 0 ? missPricePer1m : inputPricePer1m;
        }

        public BigDecimal effectiveHitPrice() {
            return hitPricePer1m.signum() > 0 ? hitPricePer1m : effectiveMissPrice();
        }

        public BigDecimal effectiveOutputPrice() {
            return outputPricePer1m;
        }

        public BigDecimal highestUnitPrice() {
            return effectiveHitPrice().max(effectiveMissPrice()).max(effectiveOutputPrice());
        }

        public Map<String, Object> toPublicMap(String maskedKey) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("engine", engine);
            map.put("template", template);
            map.put("autoFallback", autoFallback);
            map.put("baseUrl", baseUrl == null ? "" : baseUrl);
            map.put("model", model == null ? "" : model);
            map.put("keySource", keySource);
            map.put("apiKeyMasked", maskedKey);
            map.put("keyConfigured", StringUtils.hasText(byoApiKey) || StringUtils.hasText(platformApiKey));
            map.put("priceInputPer1m", inputPricePer1m.toPlainString());
            map.put("priceInputCacheHitPer1m", hitPricePer1m.toPlainString());
            map.put("priceInputCacheMissPer1m", missPricePer1m.toPlainString());
            map.put("priceOutputPer1m", outputPricePer1m.toPlainString());
            map.put("timeoutMs", timeoutMs);
            map.put("maxTokens", maxTokens);
            map.put("budgetCny", budgetCny.toPlainString());
            return map;
        }
    }
}
