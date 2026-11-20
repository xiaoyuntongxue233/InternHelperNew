package com.zhou.internhelper.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 事实校验器的契约测试：覆盖验收用例 7/8/9/10 及 AI 提及开关 */
class GreetingValidatorTest {

    private final GreetingValidator validator = new GreetingValidator(Set.of(
            "java", "springboot", "mysql", "redis", "k8s", "kubernetes", "docker", "aiagent"));

    /** 构造一份「资料里已有 SpringBoot/MySQL」的事实变量 */
    private Map<String, String> facts() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("school", "某某大学");
        v.put("schoolShort", "某大");
        v.put("degreeLabel", "本科生");
        v.put("profileSkills", "Java,SpringBoot,MySQL");
        v.put("profileEvidence", "Springboot、MySQL相关经验");
        v.put("skillClause", "熟悉Java，有SpringBoot、MySQL相关经验");
        v.put("major", "计算机科学与技术");
        v.put("grade", "2028届");
        v.put("aiAgentOk", "true");
        v.put("company", "某公司");
        v.put("title", "Java开发实习");
        return v;
    }

    private String normal(int months, int days) {
        return "您好，我是某某大学2028届本科生，熟悉Java，有SpringBoot、MySQL相关经验。"
                + "可连续实习至少" + months + "个月，每周" + days + "天。希望收到您的回复。";
    }

    @Test
    void 合规话术应通过校验() {
        GreetingValidator.Result r = validator.validate(normal(3, 4), facts(), 3, 4, 20, 300);
        assertTrue(r.ok(), () -> "不应拒绝：" + r.warnings());
        assertTrue(r.warnings().isEmpty());
    }

    @Test
    void 月数超硬上限应被拒绝() {
        GreetingValidator.Result r = validator.validate(normal(13, 4), facts(), 13, 4, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).contains("实习月数"));
    }

    @Test
    void 月数与事实不一致即使只是子串也应被拒绝() {
        // 旧实现用 contains(String.valueOf(3))，"13个月" 会被误判通过
        GreetingValidator.Result r = validator.validate(normal(13, 4), facts(), 3, 4, 20, 300);
        assertFalse(r.ok());
    }

    @Test
    void 天数超硬上限应被拒绝() {
        GreetingValidator.Result r = validator.validate(normal(3, 8), facts(), 3, 8, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).contains("出勤天数"));
    }

    @Test
    void 未提供的技能词应被拒绝() {
        String msg = normal(3, 4).replace("熟悉Java", "熟悉K8s、Java");
        GreetingValidator.Result r = validator.validate(msg, facts(), 3, 4, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).toLowerCase().contains("k8s"), r.warnings().toString());
    }

    @Test
    void 别名写法等价时不应误杀() {
        // 资料写 SpringBoot，模型写 "Spring Boot"；归一化后应等价通过
        String msg = normal(3, 4).replace("SpringBoot", "Spring Boot");
        GreetingValidator.Result r = validator.validate(msg, facts(), 3, 4, 20, 300);
        assertTrue(r.ok(), () -> "别名不应被误杀：" + r.warnings());
    }

    @Test
    void k8s与kubernetes别名双向等价() {
        Map<String, String> v = facts();
        v.put("profileSkills", "Kubernetes");
        v.put("skillClause", "熟悉Kubernetes");
        String msg = normal(3, 4).replace("熟悉Java，有SpringBoot、MySQL相关经验", "熟悉K8s");
        GreetingValidator.Result r = validator.validate(msg, v, 3, 4, 20, 300);
        assertTrue(r.ok(), () -> "K8s 与 Kubernetes 应视为同一技能：" + r.warnings());
    }

    @Test
    void 关闭AI提及时出现AI工具应被拒绝() {
        Map<String, String> v = facts();
        v.put("aiAgentOk", "false");
        String msg = "您好，我是某某大学2028届本科生。也熟悉AI agent相关工具。"
                + "可连续实习至少3个月，每周4天。希望收到您的回复。";
        GreetingValidator.Result r = validator.validate(msg, v, 3, 4, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).contains("AI"));
    }

    @Test
    void 缺少学历信息应被拒绝() {
        String msg = "您好，我是某某大学2028届。可连续实习至少3个月，每周4天。希望收到您的回复。";
        GreetingValidator.Result r = validator.validate(msg, facts(), 3, 4, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).contains("学历"));
    }

    @Test
    void 中文数字写法应被拒绝并提示用阿拉伯数字() {
        String msg = "您好，我是某某大学2028届本科生，熟悉Java，有SpringBoot、MySQL相关经验。"
                + "可连续实习至少三个月，每周四天。希望收到您的回复。";
        GreetingValidator.Result r = validator.validate(msg, facts(), 3, 4, 20, 300);
        assertFalse(r.ok());
        assertTrue(r.warnings().get(0).contains("阿拉伯数字"));
    }
}
