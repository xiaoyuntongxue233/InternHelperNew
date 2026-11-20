package com.zhou.internhelper.service;

import com.zhou.internhelper.config.AiProperties;
import com.zhou.internhelper.config.BillingProperties;
import com.zhou.internhelper.config.GreetingProperties;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 额度服务的契约测试：预检金额不虚高、限流生效。
 *
 * <p>不依赖 Mockito：Java 25 下 inline mock 存在兼容问题，
 * 这里用真实 SettingsService + 反射注入快照、JDK 动态代理 Repository，测试更稳定。</p>
 */
class AiQuotaServiceTest {

    private static final BigDecimal PRICE = new BigDecimal("1000");

    private AiUsageRepository repository;
    private AtomicReference<BigDecimal> usedCost;
    private AiQuotaService quota;

    private SettingsService.SettingsSnapshot platformSnap() {
        return new SettingsService.SettingsSnapshot(
                "AI", "concise", true,
                "https://api.deepseek.com", "deepseek-chat", "PLATFORM",
                "", "sk-platform",
                PRICE, PRICE, PRICE, PRICE,
                8000, 300, new BigDecimal("0.1"));
    }

    /** 构造真实 SettingsService 并用反射写入 snapshot 字段（测试专用） */
    private SettingsService settingsWith(SettingsService.SettingsSnapshot snap) {
        SettingsService s = new SettingsService(null, new AiProperties(),
                new BillingProperties(), new GreetingProperties(), new SecretCipher("test-key".getBytes()));
        try {
            var f = SettingsService.class.getDeclaredField("snapshot");
            f.setAccessible(true);
            f.set(s, snap);
        } catch (Exception e) {
            throw new IllegalStateException("注入快照失败", e);
        }
        return s;
    }

    @BeforeEach
    void setUp() {
        usedCost = new AtomicReference<>(BigDecimal.ZERO);
        repository = (AiUsageRepository) Proxy.newProxyInstance(
                AiUsageRepository.class.getClassLoader(),
                new Class<?>[]{AiUsageRepository.class},
                (proxy, method, args) -> {
                    if ("sumPlatformCost".equals(method.getName())) {
                        return usedCost.get();
                    }
                    if ("toString".equals(method.getName())) {
                        return "AiUsageRepositoryStub";
                    }
                    return null;
                });
        quota = new AiQuotaService(settingsWith(platformSnap()), repository);
    }

    @Test
    void 默认预估按maxTokens计算() {
        // prompt 5 字符 + 300 tokens = 305 / 1M * 1000 元 = 0.305 元
        BigDecimal cost = quota.estimateCost("ping!");
        assertEquals(0, new BigDecimal("0.305").compareTo(cost));
    }

    @Test
    void 测试连接的预估使用指定maxTokens避免虚高() {
        BigDecimal full = quota.estimateCost("ping");
        BigDecimal test = quota.estimateCost("ping", 1);
        // 全量预估 304/1M*1000=0.304；测试预估 5/1M*1000=0.005
        assertTrue(test.compareTo(full) < 0, "测试预估应小于全量预估");
        assertEquals(0, new BigDecimal("0.005").compareTo(test));
    }

    @Test
    void 额度充足时允许预检通过() {
        assertTrue(quota.canSpend(quota.estimateCost("ping", 1)));
    }

    @Test
    void 额度不足时预检拒绝() {
        usedCost.set(new BigDecimal("0.09999"));
        assertFalse(quota.canSpend(new BigDecimal("0.001")));
    }

    @Test
    void BYO模式预检恒通过() {
        SettingsService.SettingsSnapshot byo = new SettingsService.SettingsSnapshot(
                "AI", "free", true,
                "https://api.deepseek.com", "deepseek-chat", "BYO",
                "sk-user", "",
                PRICE, PRICE, PRICE, PRICE,
                8000, 300, new BigDecimal("0.1"));
        AiQuotaService byoQuota = new AiQuotaService(settingsWith(byo), repository);
        usedCost.set(new BigDecimal("10"));
        assertTrue(byoQuota.canSpend(new BigDecimal("1")));
    }

    @Test
    void 测试连接限流60秒内最多三次() {
        assertTrue(quota.tryAcquireTestSlot());
        assertTrue(quota.tryAcquireTestSlot());
        assertTrue(quota.tryAcquireTestSlot());
        assertFalse(quota.tryAcquireTestSlot(), "第 4 次应被限流");
    }

    @Test
    void 并发预留不会超额() {
        // 预算 0.1，单次预留 0.06：第一次通过，第二次应因在途 0.06 而被拒
        BigDecimal cost = new BigDecimal("0.06");
        assertEquals(cost, quota.reserve(cost), "返回的预留额应等于本次预估");
        assertNull(quota.reserve(cost), "在途预留后第二次应被拒绝");
        // 释放后可再次通过
        quota.release(cost);
        assertEquals(cost, quota.reserve(cost));
        quota.release(cost);
    }

    @Test
    void BYO模式预留返回零且不影响在途() {
        SettingsService.SettingsSnapshot byo = new SettingsService.SettingsSnapshot(
                "AI", "free", true,
                "https://api.deepseek.com", "deepseek-chat", "BYO",
                "sk-user", "",
                PRICE, PRICE, PRICE, PRICE,
                8000, 300, new BigDecimal("0.1"));
        AiQuotaService byoQuota = new AiQuotaService(settingsWith(byo), repository);
        assertEquals(0, byoQuota.reserve(new BigDecimal("10")).compareTo(BigDecimal.ZERO));
        assertEquals(0, byoQuota.reserve(new BigDecimal("10")).compareTo(BigDecimal.ZERO));
    }

    @Test
    void BYO释放不会冲掉平台在途预留() {
        // 先由 PLATFORM 预留 0.06，再由 BYO 请求 reserve/release，
        // BYO 的释放返回 ZERO，不得冲掉平台的在途额度
        BigDecimal cost = new BigDecimal("0.06");
        BigDecimal reserved = quota.reserve(cost);
        assertEquals(cost, reserved);

        SettingsService.SettingsSnapshot byo = new SettingsService.SettingsSnapshot(
                "AI", "free", true,
                "https://api.deepseek.com", "deepseek-chat", "BYO",
                "sk-user", "",
                PRICE, PRICE, PRICE, PRICE,
                8000, 300, new BigDecimal("0.1"));
        AiQuotaService byoQuota = new AiQuotaService(settingsWith(byo), repository);
        BigDecimal byoReserved = byoQuota.reserve(new BigDecimal("10"));
        assertEquals(0, byoReserved.compareTo(BigDecimal.ZERO));
        byoQuota.release(byoReserved); // ZERO，应无副作用

        assertNull(quota.reserve(cost), "BYO 释放后平台在途仍应占位，第二次预留仍被拒");
        quota.release(reserved);
    }
}
