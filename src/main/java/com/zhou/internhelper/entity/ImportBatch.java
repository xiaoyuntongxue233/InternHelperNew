package com.zhou.internhelper.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 一次导入的批次记录，用来回答「上次同步是什么时候、这次新增了多少」
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 文件名或来源描述 */
    @Column(length = 255)
    private String source;

    /** MANUAL 手动上传 / SCHEDULED 定时扫描 */
    @Column(length = 16)
    private String channel;

    private Integer received;

    private Integer added;

    private Integer skipped;

    /** 因缺少 company/title 必填字段而被跳过的条目数 */
    private Integer invalid;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Integer getReceived() { return received; }
    public void setReceived(Integer received) { this.received = received; }
    public Integer getAdded() { return added; }
    public void setAdded(Integer added) { this.added = added; }
    public Integer getSkipped() { return skipped; }
    public void setSkipped(Integer skipped) { this.skipped = skipped; }
    public Integer getInvalid() { return invalid; }
    public void setInvalid(Integer invalid) { this.invalid = invalid; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
