package com.zhou.internhelper.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型调用用量。首期按本机实例计量，userKey 固定为 local。
 */
@Entity
@Table(name = "ai_usage", indexes = {
        @Index(name = "idx_usage_user_mode", columnList = "user_key,mode")
})
public class AiUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_key", nullable = false, length = 64)
    private String userKey = "local";

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "template_id", length = 32)
    private String templateId;

    /** PLATFORM 平台额度 / BYO 用户自带 API */
    @Column(nullable = false, length = 16)
    private String mode;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** 输入 token 中缓存命中的数量 */
    @Column(name = "cache_hit_tokens")
    private Integer cacheHitTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** 本次扣费金额，单位元 */
    @Column(name = "cost_cny", nullable = false, precision = 12, scale = 4)
    private BigDecimal costCny = BigDecimal.ZERO;

    /** 提示词版本，便于追溯 */
    @Column(name = "prompt_version", length = 16)
    private String promptVersion;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCacheHitTokens() { return cacheHitTokens; }
    public void setCacheHitTokens(Integer cacheHitTokens) { this.cacheHitTokens = cacheHitTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public BigDecimal getCostCny() { return costCny; }
    public void setCostCny(BigDecimal costCny) { this.costCny = costCny; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
