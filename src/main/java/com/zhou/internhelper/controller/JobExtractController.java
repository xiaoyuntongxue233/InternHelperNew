package com.zhou.internhelper.controller;

import com.zhou.internhelper.service.JobExtractionService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 从页面文本抽取岗位字段的草稿。
 *
 * <p>只返回草稿，不写库、不触发话术生成。抓取脚本也可调用本接口拿到草稿后，
 * 按现有标准 JSON 格式写入 import-drop/，由原有导入链路消费（脚本路径零改动）。</p>
 */
@RestController
@RequestMapping("/api/jobs")
public class JobExtractController {

    private final JobExtractionService extractionService;

    public JobExtractController(JobExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    public record ExtractRequest(String pageText) {
    }

    @PostMapping("/extract")
    public Map<String, Object> extract(@RequestBody(required = false) ExtractRequest body) {
        if (body == null || !StringUtils.hasText(body.pageText())) {
            throw new IllegalArgumentException("请粘贴页面文本");
        }
        return extractionService.extract(body.pageText()).toMap();
    }
}
