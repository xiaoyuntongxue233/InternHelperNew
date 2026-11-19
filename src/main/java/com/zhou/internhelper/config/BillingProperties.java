package com.zhou.internhelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 额度配置。首期按本机实例计量，userKey 固定为 local。
 */
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    /** 默认额度，单位元 */
    private BigDecimal defaultBudgetCny = new BigDecimal("0.1");

    /** PLATFORM 使用平台额度；BYO 使用用户自带 API */
    private String mode = "PLATFORM";

    public BigDecimal getDefaultBudgetCny() { return defaultBudgetCny; }
    public void setDefaultBudgetCny(BigDecimal defaultBudgetCny) { this.defaultBudgetCny = defaultBudgetCny; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
