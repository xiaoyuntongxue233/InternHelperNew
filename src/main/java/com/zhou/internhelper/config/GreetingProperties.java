package com.zhou.internhelper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 话术生成策略配置。
 */
@ConfigurationProperties(prefix = "greeting")
public class GreetingProperties {

    /** RULE 使用模板确定性生成；AI / HYBRID 在模板草稿上做模型润色 */
    private String engine = "RULE";

    /** 提示词版本，写入用量记录便于追溯 */
    private String promptVersion = "v1";

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
}
