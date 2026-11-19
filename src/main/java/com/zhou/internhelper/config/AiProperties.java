package com.zhou.internhelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 模型调用配置。密钥和单价全部通过环境变量注入，不写入仓库。
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 模型接口地址，例如 https://api.deepseek.com */
    private String baseUrl;

    /** 模型名 */
    private String model;

    /** 平台默认密钥 */
    private String platformApiKey;

    /** 用户自带密钥，非空时进入 BYO 模式 */
    private String byoApiKey;

    /** 单价统一按 元 / 1M tokens 配置 */
    private BigDecimal priceInputPer1m = BigDecimal.ZERO;
    private BigDecimal priceInputCacheHitPer1m = BigDecimal.ZERO;
    private BigDecimal priceInputCacheMissPer1m = BigDecimal.ZERO;
    private BigDecimal priceOutputPer1m = BigDecimal.ZERO;

    /** 兼容旧单位 元 / 1K tokens；仅当对应 1M 单价未配置时生效 */
    private BigDecimal priceInputPer1k = BigDecimal.ZERO;
    private BigDecimal priceInputCacheHitPer1k = BigDecimal.ZERO;
    private BigDecimal priceInputCacheMissPer1k = BigDecimal.ZERO;
    private BigDecimal priceOutputPer1k = BigDecimal.ZERO;

    /** 单次生成最大输出 token */
    private int maxTokens = 300;

    /** 连接与读取超时，毫秒 */
    private int timeoutMs = 8000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPlatformApiKey() { return platformApiKey; }
    public void setPlatformApiKey(String platformApiKey) { this.platformApiKey = platformApiKey; }

    public String getByoApiKey() { return byoApiKey; }
    public void setByoApiKey(String byoApiKey) { this.byoApiKey = byoApiKey; }

    public BigDecimal getPriceInputPer1m() { return priceInputPer1m; }
    public void setPriceInputPer1m(BigDecimal priceInputPer1m) { this.priceInputPer1m = priceInputPer1m; }

    public BigDecimal getPriceInputCacheHitPer1m() { return priceInputCacheHitPer1m; }
    public void setPriceInputCacheHitPer1m(BigDecimal priceInputCacheHitPer1m) { this.priceInputCacheHitPer1m = priceInputCacheHitPer1m; }

    public BigDecimal getPriceInputCacheMissPer1m() { return priceInputCacheMissPer1m; }
    public void setPriceInputCacheMissPer1m(BigDecimal priceInputCacheMissPer1m) { this.priceInputCacheMissPer1m = priceInputCacheMissPer1m; }

    public BigDecimal getPriceOutputPer1m() { return priceOutputPer1m; }
    public void setPriceOutputPer1m(BigDecimal priceOutputPer1m) { this.priceOutputPer1m = priceOutputPer1m; }

    public BigDecimal getPriceInputPer1k() { return priceInputPer1k; }
    public void setPriceInputPer1k(BigDecimal priceInputPer1k) { this.priceInputPer1k = priceInputPer1k; }

    public BigDecimal getPriceInputCacheHitPer1k() { return priceInputCacheHitPer1k; }
    public void setPriceInputCacheHitPer1k(BigDecimal priceInputCacheHitPer1k) { this.priceInputCacheHitPer1k = priceInputCacheHitPer1k; }

    public BigDecimal getPriceInputCacheMissPer1k() { return priceInputCacheMissPer1k; }
    public void setPriceInputCacheMissPer1k(BigDecimal priceInputCacheMissPer1k) { this.priceInputCacheMissPer1k = priceInputCacheMissPer1k; }

    public BigDecimal getPriceOutputPer1k() { return priceOutputPer1k; }
    public void setPriceOutputPer1k(BigDecimal priceOutputPer1k) { this.priceOutputPer1k = priceOutputPer1k; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
