package com.zhou.internhelper.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 内置话术模板。首期只加载 classpath 下的简洁 / 正式两套模板，不支持用户导入。
 */
@Service
public class GreetingTemplateService {

    private static final Logger log = LoggerFactory.getLogger(GreetingTemplateService.class);

    private static final String DEFAULT_CONCISE =
            "您好，我是{{school}}{{grade}}{{degreeLabel}}{{majorClause}}。本人{{skillClause}}{{aiPart}}本人可连续实习至少{{months}}个月，每周{{days}}天{{startFromClause}}。希望收到您的回复。";
    private static final String DEFAULT_FORMAL =
            "您好！我是{{school}}{{grade}}{{degreeLabel}}{{majorClause}}。本人{{skillClause}}{{aiPart}}目前可保证连续实习{{months}}个月、每周出勤{{days}}天{{startFromClause}}。期待您的回复，感谢！";

    private final Map<String, Template> templates = new HashMap<>();

    @PostConstruct
    public void load() {
        templates.put("concise", load("concise", "templates/greeting-concise.txt", DEFAULT_CONCISE));
        templates.put("formal", load("formal", "templates/greeting-formal.txt", DEFAULT_FORMAL));
    }

    private Template load(String id, String path, String fallback) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return new Template(id, text);
        } catch (IOException e) {
            log.warn("读取内置模板 {} 失败，使用内置兜底文本：{}", path, e.getMessage());
            return new Template(id, fallback);
        }
    }

    /**
     * 用变量渲染模板。变量值不做二次解释，避免模板注入。
     */
    public Rendered render(String style, Map<String, String> variables) {
        String key = "formal".equalsIgnoreCase(style) ? "formal" : "concise";
        Template template = templates.getOrDefault(key, new Template(key, DEFAULT_CONCISE));
        String text = template.content();
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                text = text.replace("{{" + e.getKey() + "}}", value);
            }
        }
        return new Rendered(template.id(), text);
    }

    /**
     * 渲染任意文本中的事实占位符（供 AI 骨架回填使用）。
     * 与 {@link #render(String, Map)} 的区别：模板来自模型而非内置文件，
     * 因此仅做「占位符 → 后端确定性事实值」的替换，不对值做二次解释。
     * 未知占位符原样保留，交由上层校验器拦截。
     */
    public String renderText(String text, Map<String, String> variables) {
        if (text == null) {
            return "";
        }
        String result = text;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                String value = e.getValue() == null ? "" : e.getValue();
                result = result.replace("{{" + e.getKey() + "}}", value);
            }
        }
        return result;
    }

    public record Template(String id, String content) {
    }

    public record Rendered(String templateId, String text) {
    }
}
