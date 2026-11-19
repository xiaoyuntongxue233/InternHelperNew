package com.zhou.internhelper.entity;

import jakarta.persistence.*;

/**
 * 求职者个人资料（单行，id 固定为 1）。
 * 话术生成器的输入：身份、技能、可实习时长与出勤、句式风格。
 */
@Entity
@Table(name = "candidate_profile")
public class CandidateProfile {

    @Id
    private Long id = 1L;

    /** 姓名，可留空 */
    private String name;

    /** 学校全称（含校区），如「某大学（某校区）」 */
    private String schoolFull;

    /** 学校简称，如「某大学」 */
    private String schoolShort;

    private String major;

    /** 年级，如「2028届」 */
    private String grade;

    /** 学历，本科 / 硕士 */
    private String degree = "本科";

    /** 技能标签，逗号分隔，如「Java,SpringBoot,MySQL」 */
    private String skills;

    /** 技能佐证句，格式：key=句子;key=句子，如 java=独立做过RBAC项目 */
    @Column(length = 2000)
    private String evidence;

    /** 可连续实习最少月数（上限 6） */
    private Integer minMonths = 3;

    /** 可连续实习最多月数（上限 6） */
    private Integer maxMonths = 6;

    /** 每周可出勤天数（上限 5） */
    private Integer daysPerWeek = 5;

    /** 可开始时间，如「1 月中旬」 */
    private String startFrom;

    /** 是否强调会用 AI agent */
    private Boolean aiAgentOk = Boolean.TRUE;

    /** 句式风格：concise / formal */
    private String style = "concise";

    private Integer minLen = 55;
    private Integer maxLen = 110;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSchoolFull() { return schoolFull; }
    public void setSchoolFull(String schoolFull) { this.schoolFull = schoolFull; }

    public String getSchoolShort() { return schoolShort; }
    public void setSchoolShort(String schoolShort) { this.schoolShort = schoolShort; }

    public String getMajor() { return major; }
    public void setMajor(String major) { this.major = major; }

    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }

    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }

    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public Integer getMinMonths() { return minMonths; }
    public void setMinMonths(Integer minMonths) { this.minMonths = minMonths; }

    public Integer getMaxMonths() { return maxMonths; }
    public void setMaxMonths(Integer maxMonths) { this.maxMonths = maxMonths; }

    public Integer getDaysPerWeek() { return daysPerWeek; }
    public void setDaysPerWeek(Integer daysPerWeek) { this.daysPerWeek = daysPerWeek; }

    public String getStartFrom() { return startFrom; }
    public void setStartFrom(String startFrom) { this.startFrom = startFrom; }

    public Boolean getAiAgentOk() { return aiAgentOk; }
    public void setAiAgentOk(Boolean aiAgentOk) { this.aiAgentOk = aiAgentOk; }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }

    public Integer getMinLen() { return minLen; }
    public void setMinLen(Integer minLen) { this.minLen = minLen; }

    public Integer getMaxLen() { return maxLen; }
    public void setMaxLen(Integer maxLen) { this.maxLen = maxLen; }
}
