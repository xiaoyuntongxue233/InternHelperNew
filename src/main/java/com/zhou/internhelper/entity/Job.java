package com.zhou.internhelper.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 岗位
 */
@Entity
@Table(name = "job",
        uniqueConstraints = @UniqueConstraint(name = "uk_job_title_hash", columnNames = "title_hash"),
        indexes = {
                @Index(name = "idx_job_company", columnList = "company"),
                @Index(name = "idx_job_region", columnList = "province,city,district"),
                @Index(name = "idx_job_status_id", columnList = "status,id")
        })
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公司 */
    @Column(nullable = false, length = 128)
    private String company;

    /** 岗位名称 */
    @Column(nullable = false, length = 255)
    private String title;

    /**
     * 公司+岗位 的归一化哈希（MD5，32 位十六进制），用于数据库级去重。
     * 直接对 company+title 建联合唯一索引会超出 MySQL utf8mb4 的键长度限制，故用哈希列。
     * 归一化：trim + 小写；哈希由 JobService 统一计算。
     */
    @Column(name = "title_hash", length = 32)
    private String titleHash;

    /** 软删除标记：删除岗位时不物理删除，保留投递记录与统计口径 */
    @Column(nullable = false)
    private boolean deleted = false;

    /** 省 / 直辖市（如 广东、北京） */
    @Column(length = 32)
    private String province;

    /** 城市（如 广州、北京） */
    @Column(length = 32)
    private String city;

    /** 区县（如 海珠区） */
    @Column(length = 64)
    private String district;

    /** 原始地点字符串（如 广州海珠区），用于回溯 */
    @Column(length = 128)
    private String rawLocation;

    /** 薪资描述（元/天 或 K），由结构化字段生成，兼容历史文本 */
    @Column(length = 64)
    private String salary;

    /** 薪资类型：DAILY 日薪 / MONTHLY 月薪 */
    @Column(name = "salary_type", length = 16)
    private String salaryType;

    /** 日薪填 元/天，月薪填 元/月 */
    @Column(name = "salary_min")
    private Integer salaryMin;

    @Column(name = "salary_max")
    private Integer salaryMax;

    /** 职位链接（BOSS 带 securityId 的链接可达 600+ 字符，留足余量） */
    @Column(length = 2048)
    private String url;

    /** 技术栈关键词，逗号分隔 */
    @Column(length = 512)
    private String skills;

    /** 岗位要求原文（截断保存） */
    @Column(length = 2000)
    private String jdText;

    /** 实习时长（月），从岗位要求解析 */
    @Column
    private Integer months;

    /** 每周出勤（天），从岗位要求解析 */
    @Column
    private Integer days;

    /** 折算最低日薪（元/天） */
    @Column(name = "daily_min")
    private Integer dailyMin;

    /** 折算最高日薪（元/天） */
    @Column(name = "daily_max")
    private Integer dailyMax;

    /** NEW 未联系 / CONTACTED 已联系 / SKIPPED 已跳过 */
    @Column(length = 16, nullable = false)
    private String status = "NEW";

    /** 岗位类型：INTERN 实习 / FULLTIME 社招（薪资和年限明显不是实习） */
    @Column(length = 16)
    private String category = "INTERN";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTitleHash() { return titleHash; }
    public void setTitleHash(String titleHash) { this.titleHash = titleHash; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getRawLocation() { return rawLocation; }
    public void setRawLocation(String rawLocation) { this.rawLocation = rawLocation; }
    public String getSalary() { return salary; }
    public void setSalary(String salary) { this.salary = salary; }
    public String getSalaryType() { return salaryType; }
    public void setSalaryType(String salaryType) { this.salaryType = salaryType; }
    public Integer getSalaryMin() { return salaryMin; }
    public void setSalaryMin(Integer salaryMin) { this.salaryMin = salaryMin; }
    public Integer getSalaryMax() { return salaryMax; }
    public void setSalaryMax(Integer salaryMax) { this.salaryMax = salaryMax; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }
    public String getJdText() { return jdText; }
    public void setJdText(String jdText) { this.jdText = jdText; }
    public Integer getMonths() { return months; }
    public void setMonths(Integer months) { this.months = months; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public Integer getDailyMin() { return dailyMin; }
    public void setDailyMin(Integer dailyMin) { this.dailyMin = dailyMin; }
    public Integer getDailyMax() { return dailyMax; }
    public void setDailyMax(Integer dailyMax) { this.dailyMax = dailyMax; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
