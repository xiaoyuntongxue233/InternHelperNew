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
        // 1) 限流：所有尝试（含失败）都占名额，防止无鉴权场景下反复点击刷接口
        if (!quota.tryAcquireTestSlot()) {
            return AiGreetingClient.TestResult.failure("测试过于频繁，请 60 秒后再试。");
        }

        SettingsService.SettingsSnapshot s = settings.snapshot();
        Map<String, String> b = body == null ? Map.of() : body;
        String baseUrl = value(b, "baseUrl", s.baseUrl());
        String model = value(b, "model", s.model());
        Integer timeout = null;
        try {
            String t = value(b, "timeoutMs", null);
            if (t != null && !t.isBlank()) {
                timeout = Integer.parseInt(t);
            }
        } catch (NumberFormatException ignored) {
            timeout = null;
        }

        // 2) 按「本次实际使用的 Key」决定模式，而非当前快照开关：
        //    用户临时填了新 Key（且不等于平台 Key）→ BYO，不占用平台额度；
        //    留空或填的就是平台 Key → PLATFORM，走额度预检。
        String provided = b.get("apiKey");
        String platformKey = s.platformApiKey();
        String apiKey;
        boolean byo;
        if (provided != null && !provided.isBlank()) {
            apiKey = provided.trim();
            byo = !apiKey.equals(platformKey);
        } else {
            apiKey = s.activeApiKey();
            byo = s.useByo();
        }
        String mode = byo ? "BYO" : "PLATFORM";

        // 3) 预检：仅 PLATFORM 模式受额度约束。测试请求固定 max_tokens=1，避免虚高误拒
        BigDecimal estimate = quota.estimateCost(AiGreetingClient.TEST_PROMPT, 1);
        if (!byo && !quota.canSpend(estimate)) {
            return AiGreetingClient.TestResult.failure(
                    "默认额度已用完，测试连接已跳过；如需继续，请自行接入 API。");
        }

        // 4) 调用；5) 仅成功时记账，且按本次实际模式记录（鉴权失败/网络错误不扣费）
        AiGreetingClient.TestResult result = aiClient.testConnection(baseUrl, model, apiKey, timeout);
        if (result.success()) {
            quota.charge(null, "test-connection", result.promptTokens(), result.cacheHitTokens(),
                    result.completionTokens(), result.usageReported(), estimate, "test", mode);
        }
        return result;
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
