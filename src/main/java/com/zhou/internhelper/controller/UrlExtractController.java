package com.zhou.internhelper.controller;

import com.zhou.internhelper.service.UrlExtractionService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页端「贴网址自动取数」：提交任务 → 轮询状态 → 拿到草稿回填表单。
 * 只产出草稿，不写库。
 */
@RestController
@RequestMapping("/api/jobs/extract/url")
public class UrlExtractController {

    private final UrlExtractionService service;

    public UrlExtractController(UrlExtractionService service) {
        this.service = service;
    }

    public record UrlRequest(String url) {
    }

    @PostMapping
    public Map<String, Object> submit(@RequestBody(required = false) UrlRequest body) {
        if (body == null || !StringUtils.hasText(body.url())) {
            throw new IllegalArgumentException("请提供岗位链接");
        }
        UrlExtractionService.SubmitResult result = service.submit(body.url().trim());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", result.jobId());
        map.put("status", result.status());
        return map;
    }

    @GetMapping("/{jobId}")
    public Map<String, Object> status(@PathVariable String jobId) {
        UrlExtractionService.StatusResult result = service.status(jobId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", result.status());
        map.put("draft", result.draft() == null ? Map.of() : result.draft());
        map.put("message", result.message());
        return map;
    }
}
