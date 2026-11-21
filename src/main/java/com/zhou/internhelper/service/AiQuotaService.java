package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 额度控制。首期按本机实例计量，userKey 固定为 local。
 * PLATFORM 模式使用平台额度；BYO 模式使用用户自带 API，不扣额度。
 * 单价、模式、最大输出均从 SettingsService 快照读取，网页保存后立即生效。
 */
@Service
public class AiQuotaService {

    public static final String LOCAL_USER = "local";

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** 测试连接限流：60 秒窗口内最多 3 次尝试（含失败），防止无鉴权场景下刷接口 */
    private static final long TEST_WINDOW_MS = 60_000L;
    private static final int TEST_MAX_ATTEMPTS = 3;
    private final java.util.ArrayDeque<Long> testAttempts = new java.util.ArrayDeque<>();

    /**
     * 在途预留金额：并发请求预检通过后先预留预估金额，避免多个请求同时看到低用量而全部放行。
     * charge 或失败时通过 release 释放。进程内状态，单实例有效。
     */
    private BigDecimal inFlight = BigDecimal.ZERO;

    private final SettingsService settings;
    private final AiUsageRepository repository;

    public AiQuotaService(SettingsService settings, AiUsageRepository repository) {
        this.settings = settings;
        this.repository = repository;
    }

    /**
     * 尝试获取一次测试连接名额。所有尝试（含鉴权失败）都计入窗口，
     * 避免用失败请求绕过限流。进程内状态，重启后清零，与单实例约束一致。
     */
    public synchronized boolean tryAcquireTestSlot() {
        long now = System.currentTimeMillis();
        while (!testAttempts.isEmpty() && now - testAttempts.peekFirst() > TEST_WINDOW_MS) {
            testAttempts.pollFirst();
        }
        if (testAttempts.size() >= TEST_MAX_ATTEMPTS) {
            return false;
        }
        testAttempts.addLast(now);
        return true;
    }

    private SettingsService.SettingsSnapshot snap() {
        return settings.snapshot();
    }

    /** 是否使用用户自带 API */
    public boolean useByo() {
        return snap().useByo();
    }

    /** 当前生效的 API Key */
    public String activeApiKey() {
        return snap().activeApiKey() == null ? "" : snap().activeApiKey();
    }

    /** 保守预估：按字符数上界估算 prompt token，再加最大输出 token */
    public BigDecimal estimateCost(String prompt) {
        return estimateCost(prompt, snap().maxTokens());
    }

    /**
     * 指定输出上限的保守预估。测试连接实际只用 max_tokens=1，
     * 若沿用默认的 maxTokens（如 300）会把预估金额放大数百倍，导致误拒。
     */
    public BigDecimal estimateCost(String prompt, int maxTokens) {
        BigDecimal unit = snap().highestUnitPrice();
        if (unit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int promptTokens = prompt == null ? 0 : prompt.length();
        long billable = (long) promptTokens + Math.max(0, maxTokens);
        return BigDecimal.valueOf(billable)
                .divide(MILLION, 6, RoundingMode.HALF_UP)
                .multiply(unit)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 调用前预检（不含在途预留）。价格未配置时拒绝使用平台额度，避免无限制调用。 */
    public boolean canSpend(BigDecimal estimatedCost) {
        if (useByo()) {
            return true;
        }
        if (snap().highestUnitPrice().signum() <= 0) {
            return false;
        }
        BigDecimal cost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        BigDecimal used = repository.sumPlatformCost(LOCAL_USER);
        return used.add(cost).compareTo(snap().budgetCny()) <= 0;
    }

    /**
     * 并发安全的预检 + 预留：把预估金额计入在途，后续请求会看到它，从而避免并发超额。
     * 返回值即本次实际预留额，供 release 精确释放：
     *   null   = 拒绝调用（额度不足或未配置单价）
     *   ZERO   = 允许但无需预留（BYO 模式，不占用平台在途）
     *   正数   = 已预留金额
     * 必须在调用模型前调用；无论成功或失败，调用方都必须用返回值配对 release。
     */
    public synchronized BigDecimal reserve(BigDecimal estimatedCost) {
        if (useByo()) {
            return BigDecimal.ZERO;
        }
        if (snap().highestUnitPrice().signum() <= 0) {
            return null;
        }
        BigDecimal cost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        BigDecimal used = repository.sumPlatformCost(LOCAL_USER);
        if (used.add(inFlight).add(cost).compareTo(snap().budgetCny()) > 0) {
            return null;
        }
        inFlight = inFlight.add(cost);
        return cost;
    }

    /**
     * 释放在途预留：只递减本次真正预留的金额。
     * null / ZERO 不递减，避免 BYO 请求误释放掉其他 PLATFORM 请求的在途额度。
     */
    public synchronized void release(BigDecimal reserved) {
        if (reserved == null || reserved.signum() <= 0) {
            return;
        }
        inFlight = inFlight.subtract(reserved).max(BigDecimal.ZERO);
    }

    /** 剩余平台额度，单位元 */
    public BigDecimal remainingCny() {
        BigDecimal used = repository.sumPlatformCost(LOCAL_USER);
        BigDecimal left = snap().budgetCny().subtract(used);
        return left.signum() < 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : left.setScale(4, RoundingMode.HALF_UP);
    }

    /** 已用平台额度，单位元 */
    public BigDecimal usedCny() {
        return repository.sumPlatformCost(LOCAL_USER).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 自带 Key 的金额消耗量（按当前配置的单价和已记录的 token 估算）。
     * BYO 调用不扣平台额度，历史记录的 costCny 固定为 0，因此这里从 token 重新计算，
     * 便于用户了解自己的 Key 大约花了多少钱。
     */
    public Map<String, Object> byoUsage() {
        List<AiUsage> rows = repository.findByUserKeyAndMode(LOCAL_USER, "BYO");
        BigDecimal total = BigDecimal.ZERO;
        for (AiUsage row : rows) {
            total = total.add(estimatedCostCny(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", rows.size());
        result.put("usedCny", total.setScale(4, RoundingMode.HALF_UP).toPlainString());
        return result;
    }

    /** 按当前单价估算单条用量金额；PLATFORM 与 BYO 展示统一口径 */
    public BigDecimal estimatedCostCny(AiUsage row) {
        if (row == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return costFromTokens(nullToZero(row.getPromptTokens()),
                nullToZero(row.getCacheHitTokens()), nullToZero(row.getCompletionTokens()));
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private BigDecimal costFromTokens(int promptTokens, int cacheHitTokens, int completionTokens) {
        SettingsService.SettingsSnapshot s = snap();
        int hit = Math.max(0, Math.min(cacheHitTokens, promptTokens));
        int miss = Math.max(0, promptTokens - hit);
        BigDecimal hitCost = BigDecimal.valueOf(hit)
                .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(s.effectiveHitPrice());
        BigDecimal missCost = BigDecimal.valueOf(miss)
                .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(s.effectiveMissPrice());
        BigDecimal outputCost = BigDecimal.valueOf(completionTokens)
                .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(s.effectiveOutputPrice());
        return hitCost.add(missCost).add(outputCost).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 调用后记账。模型返回 usage 时按实际扣费；未返回时按预检的保守金额扣费。
     * 模式取当前快照，供话术生成路径使用。
     */
    public void charge(Long jobId, String templateId, int promptTokens, int cacheHitTokens,
                       int completionTokens, boolean usageReported, BigDecimal estimatedCost,
                       String promptVersion) {
        charge(jobId, templateId, promptTokens, cacheHitTokens, completionTokens,
                usageReported, estimatedCost, promptVersion, null);
    }

    /**
     * 带显式模式的记账，供测试连接使用：测试可能用临时填入的 Key，
     * 其模式由「实际使用的 Key」决定，而非当前快照开关。mode=null 时沿用快照。
     */
    @Transactional
    public void charge(Long jobId, String templateId, int promptTokens, int cacheHitTokens,
                       int completionTokens, boolean usageReported, BigDecimal estimatedCost,
                       String promptVersion, String mode) {
        String effectiveMode = mode == null ? (useByo() ? "BYO" : "PLATFORM") : mode;
        AiUsage usage = new AiUsage();
        usage.setUserKey(LOCAL_USER);
        usage.setJobId(jobId);
        usage.setTemplateId(templateId);
        usage.setMode(effectiveMode);
        usage.setPromptTokens(promptTokens);
        usage.setCacheHitTokens(cacheHitTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setPromptVersion(promptVersion);
        usage.setCostCny(resolveCost(promptTokens, cacheHitTokens, completionTokens,
                usageReported, estimatedCost, effectiveMode));
        repository.save(usage);
    }

    private BigDecimal resolveCost(int promptTokens, int cacheHitTokens, int completionTokens,
                                   boolean usageReported, BigDecimal estimatedCost) {
        return resolveCost(promptTokens, cacheHitTokens, completionTokens,
                usageReported, estimatedCost, useByo() ? "BYO" : "PLATFORM");
    }

    private BigDecimal resolveCost(int promptTokens, int cacheHitTokens, int completionTokens,
                                   boolean usageReported, BigDecimal estimatedCost, String mode) {
        if ("BYO".equals(mode)) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (usageReported && snap().highestUnitPrice().signum() > 0) {
            return costFromTokens(promptTokens, cacheHitTokens, completionTokens);
        }
        return estimatedCost == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : estimatedCost.setScale(4, RoundingMode.HALF_UP);
    }
}
