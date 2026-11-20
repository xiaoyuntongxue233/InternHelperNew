package com.zhou.internhelper.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 话术草稿留痕：每次生成 / 重新生成 / 手动编辑保存各追加一条，可回看与选用。
 * 注意这是「话术草稿」，不是投递记录——不消耗额外 AI 额度，也不计入转化统计。
 */
@Entity
@Table(name = "greeting_version", indexes = {
        @Index(name = "idx_gv_job_time", columnList = "jobId,createdAt")
})
public class GreetingVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long jobId;

    @Column(nullable = false, length = 2000)
    private String message;

    /** RULE / AI */
    private String engine;

    private String templateId;

    private String direction;

    private Integer months;

    private Integer days;

    /** 来源：GENERATE / REGEN / EDIT */
    private String source;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getMonths() { return months; }
    public void setMonths(Integer months) { this.months = months; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
