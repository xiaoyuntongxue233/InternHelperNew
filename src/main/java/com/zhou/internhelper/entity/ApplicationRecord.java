package com.zhou.internhelper.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 投递记录：一次「打招呼」的留痕，用于统计转化率
 */
@Entity
@Table(name = "application_record", indexes = {
        @Index(name = "idx_record_sent_at", columnList = "sentAt"),
        @Index(name = "idx_record_company", columnList = "company")
})
public class ApplicationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    @Column(nullable = false, length = 128)
    private String company;

    @Column(length = 255)
    private String title;

    /** 实际发出的打招呼内容（与话术保存接口的 2000 字上限保持一致） */
    @Column(length = 2000)
    private String message;

    /** 是否发送成功 */
    @Column(nullable = false)
    private Boolean success = true;

    /** 失败原因 */
    @Column(length = 255)
    private String failReason;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
