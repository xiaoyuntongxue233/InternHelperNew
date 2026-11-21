package com.zhou.internhelper.service;

/**
 * 岗位信息抽取使用的模型能力，单独抽成接口便于单测用 JDK 动态代理桩替换，
 * 不把测试绑死在 AiGreetingClient 的具体实现上。
 */
public interface ExtractionAiClient {

    /** 构造抽取提示词，供调用前估算成本使用 */
    String buildExtractUserPrompt(String pageText);

    /**
     * 调用模型抽取岗位信息。
     * 返回的 {@code message} 是模型输出的原始 JSON 文本，校验由 JobExtractionService 负责。
     */
    AiGreetingClient.AiResult extract(String prompt);
}
