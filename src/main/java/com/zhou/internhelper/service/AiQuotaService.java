package com.zhou.internhelper.service;

import com.zhou.internhelper.config.AiProperties;
import com.zhou.internhelper.config.BillingProperties;
import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 额度控制。首期按本机实例计量，userKey 固定为 local。
 * PLATFORM 模式使用平台额度；BYO 模式使用用户自带 API，不扣额度。
 */
@Service
public class AiQuotaService {

    public static final String LOCAL_USER = "local";

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AiProperties ai;
    private final BillingProperties billing;
    private final AiUsageRepository repository;

    public AiQuotaService(AiProperties ai, BillingProperties billing, AiUsageRepository repository) {
        this.ai = ai;
        this.billing = billing;
        this.repository = repository;
    }

    /** 是否使用用户自带 API */
    public boolean useByo() {
        return "BYO".equalsIgnoreCase(billing.getMode()) || StringUtils.hasText(ai.getByoApiKey());
    }

    /** 当前生效的 API Key */
    public String activeApiKey() {
        return useByo() ? nullToEmpty(ai.getByoApiKey()) : nullToEmpty(ai.getPlatformApiKey());
    }

    /** 统一换算为 元 / 1M tokens；1K 单价乘以 1000 得到 1M 单价 */
    private BigDecimal toPerMillion(BigDecimal per1m, BigDecimal per1k) {
        if (per1m != null && per1m.signum() > 0) {
            return per1m;
        }
        if (per1k != null && per1k.signum() > 0) {
            return per1k.multiply(THOUSAND);
        }
        return BigDecimal.ZERO;
    }

    /** 缓存未命中的输入单价，单位 元 / 1M tokens */
    private BigDecimal missPrice() {
        BigDecimal miss = toPerMillion(ai.getPriceInputCacheMissPer1m(), ai.getPriceInputCacheMissPer1k());
        if (miss.signum() > 0) {
            return miss;
        }
        return toPerMillion(ai.getPriceInputPer1m(), ai.getPriceInputPer1k());
    }

    /** 缓存命中的输入单价，单位 元 / 1M tokens；未配置时按未命中价保守估算 */
    private BigDecimal hitPrice() {
        BigDecimal hit = toPerMillion(ai.getPriceInputCacheHitPer1m(), ai.getPriceInputCacheHitPer1k());
        return hit.signum() > 0 ? hit : missPrice();
    }

    /** 输出单价，单位 元 / 1M tokens */
    private BigDecimal outputPrice() {
        return toPerMillion(ai.getPriceOutputPer1m(), ai.getPriceOutputPer1k());
    }

    /** 预检用最高单价，确保保守 */
    private BigDecimal unitPrice() {
        return hitPrice().max(missPrice()).max(outputPrice());
    }

    /** 保守预估：按字符数上界估算 prompt token，再加最大输出 token */
    public BigDecimal estimateCost(String prompt) {
        BigDecimal unit = unitPrice();
        if (unit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int promptTokens = prompt == null ? 0 : prompt.length();
        long billable = (long) promptTokens + ai.getMaxTokens();
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
        if (unitPrice().signum() <= 0) {
            return false;
        }
        BigDecimal cost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        BigDecimal used = repository.sumPlatformCost(LOCAL_USER);
        return used.add(cost).compareTo(billing.getDefaultBudgetCny()) <= 0;
    }

    /** 剩余平台额度，单位元 */
    public BigDecimal remainingCny() {
        BigDecimal used = repository.sumPlatformCost(LOCAL_USER);
        BigDecimal left = billing.getDefaultBudgetCny().subtract(used);
        return left.signum() < 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : left.setScale(4, RoundingMode.HALF_UP);
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
        if (usageReported && unitPrice().signum() > 0) {
            int hit = Math.max(0, Math.min(cacheHitTokens, promptTokens));
            int miss = Math.max(0, promptTokens - hit);
            BigDecimal hitCost = BigDecimal.valueOf(hit)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(hitPrice());
            BigDecimal missCost = BigDecimal.valueOf(miss)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(missPrice());
            BigDecimal outputCost = BigDecimal.valueOf(completionTokens)
                    .divide(MILLION, 6, RoundingMode.HALF_UP).multiply(outputPrice());
            return hitCost.add(missCost).add(outputCost).setScale(4, RoundingMode.HALF_UP);
        }
        return estimatedCost == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : estimatedCost.setScale(4, RoundingMode.HALF_UP);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
