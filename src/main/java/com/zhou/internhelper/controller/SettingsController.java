package com.zhou.internhelper.controller;

import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import com.zhou.internhelper.service.AiGreetingClient;
import com.zhou.internhelper.service.AiQuotaService;
import com.zhou.internhelper.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置接口。配置存数据库、热更新；API Key 只返回脱敏值。
 * 本应用无鉴权，仅限本机使用，不要部署到公网。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final AiQuotaService quota;
    private final AiGreetingClient aiClient;
    private final AiUsageRepository usageRepository;

    public SettingsController(SettingsService settings, AiQuotaService quota,
                              AiGreetingClient aiClient, AiUsageRepository usageRepository) {
        this.settings = settings;
        this.quota = quota;
        this.aiClient = aiClient;
        this.usageRepository = usageRepository;
    }

    @GetMapping
    public Map<String, Object> get() {
        SettingsService.SettingsSnapshot s = settings.snapshot();
        Map<String, Object> map = new LinkedHashMap<>();
        String activeKey = s.useByo() ? s.byoApiKey() : s.platformApiKey();
        map.put("settings", s.toPublicMap(settings.maskKey(activeKey)));
        map.put("quota", quotaInfo());
        return map;
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> patch) {
        settings.validatePatch(patch);
        SettingsService.SettingsSnapshot s = settings.update(patch);
        String activeKey = s.useByo() ? s.byoApiKey() : s.platformApiKey();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", true);
        map.put("settings", s.toPublicMap(settings.maskKey(activeKey)));
        map.put("quota", quotaInfo());
        return map;
    }

    @PostMapping("/test-connection")
    public AiGreetingClient.TestResult test(@RequestBody(required = false) Map<String, String> body) {
        SettingsService.SettingsSnapshot s = settings.snapshot();
        Map<String, String> b = body == null ? Map.of() : body;
        String baseUrl = value(b, "baseUrl", s.baseUrl());
        String model = value(b, "model", s.model());
        String apiKey = value(b, "apiKey", s.activeApiKey());
        Integer timeout = null;
        try {
            String t = value(b, "timeoutMs", null);
            if (t != null && !t.isBlank()) {
                timeout = Integer.parseInt(t);
            }
        } catch (NumberFormatException ignored) {
            timeout = null;
        }
        return aiClient.testConnection(baseUrl, model, apiKey, timeout);
    }

    @GetMapping("/usage")
    public List<AiUsage> usage() {
        return usageRepository.findTop20ByOrderByCreatedAtDesc();
    }

    private Map<String, Object> quotaInfo() {
        BigDecimal used = quota.usedCny();
        BigDecimal budget = settings.snapshot().budgetCny();
        BigDecimal remaining = quota.remainingCny();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("usedCny", used.toPlainString());
        map.put("budgetCny", budget.toPlainString());
        map.put("remainingCny", remaining.toPlainString());
        map.put("mode", settings.snapshot().useByo() ? "BYO" : "PLATFORM");
        map.put("percentUsed", budget.signum() == 0 ? 0
                : used.multiply(BigDecimal.valueOf(100)).divide(budget, 0, RoundingMode.HALF_UP).intValue());
        return map;
    }

    private String value(Map<String, String> body, String key, String fallback) {
        String v = body.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
