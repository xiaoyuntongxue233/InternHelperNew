package com.zhou.internhelper.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 打招呼话术规则：关键词 → 技能句
 * 数据驱动，方便后续加规则而不用改代码。
 */
@Entity
@Table(name = "greeting_rule")
public class GreetingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 匹配用的正则（对 岗位名 + 技术栈 + 要求原文 做匹配） */
    @Column(nullable = false, length = 255)
    private String pattern;

    /** 命中后使用的技能句 */
    @Column(nullable = false, length = 255)
    private String skillClause;

    /** 方向名称，仅用于展示和统计 */
    @Column(length = 64)
    private String direction;

    /** 数字越小优先级越高 */
    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getSkillClause() { return skillClause; }
    public void setSkillClause(String skillClause) { this.skillClause = skillClause; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
