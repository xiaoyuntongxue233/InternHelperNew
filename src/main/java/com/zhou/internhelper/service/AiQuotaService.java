package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 额度控制。首期按本机实例计量，userKey 固定为 local。
 * PLATFORM 模式使用平台额度；BYO 模式使用用户自带 API，不扣额度。
 * 单价、模式、最大输出均从 SettingsService 快照读取，网页保存后立即生效。
 */
@Service
public class AiQuotaService {

    public static final String LOCAL_USER = "local";

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final SettingsService settings;
    private final AiUsageRepository repository;

    public AiQuotaService(SettingsService settings, AiUsageRepository repository) {
        this.settings = settings;
        this.repository = repository;
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
        BigDecimal unit = snap().highestUnitPrice();
        if (unit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int promptTokens = prompt == null ? 0 : prompt.length();
        long billable = (long) promptTokens + snap().maxTokens();
        return BigDecimal.valueOf(billable)
                .divide(MILLION, 6, RoundingMode.HALF_UP)
                .multiply(unit)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 调用前预检。价格未配置时拒绝使用平台额度，避免无限制调用。 */
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
     * 调用后记账。模型返回 usage 时按实际扣费；未返回时按预检的保守金额扣费。
     */
    @Transactional
    public void charge(Long jobId, String templateId, int promptTokens, int cacheHitTokens,
                       int completionTokens, boolean usageReported, BigDecimal estimatedCost,
                       String promptVersion) {
        AiUsage usage = new AiUsage();
        usage.setUserKey(LOCAL_USER);
        usage.setJobId(jobId);
        usage.setTemplateId(templateId);
        usage.setMode(useByo() ? "BYO" : "PLATFORM");
        usage.setPromptTokens(promptTokens);
        usage.setCacheHitTokens(cacheHitTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setPromptVersion(promptVersion);
        usage.setCostCny(resolveCost(promptTokens, cacheHitTokens, completionTokens, usageReported, estimatedCost));
        repository.save(usage);
    }

    private BigDecimal resolveCost(int promptTokens, int cacheHitTokens, int completionTokens,
                                   boolean usageReported, BigDecimal estimatedCost) {
        if (useByo()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (usageReported && snap().highestUnitPrice().signum() > 0) {
            int hit = Math.max(0, Math.min(cacheHitTokens, promptTokens));
            int miss = Math.max(0, promptTokens - hit);
            BigDecimal hitCost = BigDecimal.valueOf(hit)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(snap().effectiveHitPrice());
            BigDecimal missCost = BigDecimal.valueOf(miss)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(snap().effectiveMissPrice());
            BigDecimal outputCost = BigDecimal.valueOf(completionTokens)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(snap().effectiveOutputPrice());
            return hitCost.add(missCost).add(outputCost).setScale(4, RoundingMode.HALF_UP);
        }
        return estimatedCost == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : estimatedCost.setScale(4, RoundingMode.HALF_UP);
    }
}
