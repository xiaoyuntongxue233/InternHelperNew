package com.zhou.internhelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 候选人信息（学校 / 届别）。
 * 放在配置里而不是写死，一是方便换人使用，二是避免把个人信息提交进代码库。
 */
@ConfigurationProperties(prefix = "app.candidate")
public class CandidateProperties {

    /** 学校名称 */
    private String school = "某大学";

    /** 届别，如 2028届 */
    private String grade = "2028届";

    public String getSchool() { return school; }
    public void setSchool(String school) { this.school = school; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
}
