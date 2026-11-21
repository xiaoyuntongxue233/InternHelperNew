package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiQuotaServiceByoTest {

    private AiQuotaService serviceWith(List<AiUsage> byoRows) {
        SettingsService.SettingsSnapshot snap = new SettingsService.SettingsSnapshot(
                "AI", "concise", true,
                "https://api.deepseek.com", "deepseek-flash", "BYO",
                "", "sk-byo",
                BigDecimal.ZERO,
                new BigDecimal("0.04"), new BigDecimal("2"), new BigDecimal("8"),
                8000, 300, new BigDecimal("0.1"));
        SettingsService settings = new SettingsService(null, new com.zhou.internhelper.config.AiProperties(),
                new com.zhou.internhelper.config.BillingProperties(),
                new com.zhou.internhelper.config.GreetingProperties(),
                new SecretCipher("test-key".getBytes()));
        try {
            var f = SettingsService.class.getDeclaredField("snapshot");
            f.setAccessible(true);
            f.set(settings, snap);
        } catch (Exception e) {
            throw new IllegalStateException("注入快照失败", e);
        }
        AiUsageRepository repository = (AiUsageRepository) Proxy.newProxyInstance(
                AiUsageRepository.class.getClassLoader(),
                new Class<?>[]{AiUsageRepository.class},
                (proxy, method, args) -> {
                    if ("findByUserKeyAndMode".equals(method.getName())) return byoRows;
                    if ("sumPlatformCost".equals(method.getName())) return BigDecimal.ZERO;
                    return null;
                });
        return new AiQuotaService(settings, repository);
    }

    private AiUsage row(int prompt, int hit, int completion) {
        AiUsage u = new AiUsage();
        u.setMode("BYO");
        u.setPromptTokens(prompt);
        u.setCacheHitTokens(hit);
        u.setCompletionTokens(completion);
        u.setCostCny(BigDecimal.ZERO);
        return u;
    }

    @Test
    void BYO金额按当前单价从token估算() {
        AiQuotaService service = serviceWith(List.of(
                row(1_000_000, 200_000, 100_000),
                row(100_000, 0, 0)));
        Map<String, Object> usage = service.byoUsage();
        assertEquals(2, usage.get("calls"));
        // 0.04*0.2 + 2*0.8 + 8*0.1 + 2*0.1 = 2.608
        assertEquals("2.6080", usage.get("usedCny"));
    }

    @Test
    void 空BYO记录返回零() {
        AiQuotaService service = serviceWith(List.of());
        Map<String, Object> usage = service.byoUsage();
        assertEquals(0, usage.get("calls"));
        assertEquals("0.0000", usage.get("usedCny"));
    }
}
