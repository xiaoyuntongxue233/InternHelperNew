package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.internhelper.entity.ImportBatch;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ImportBatchRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 岗位导入：
 * 1. 手动：POST /api/import/jobs
 * 2. 定时：每 5 分钟扫描 app.import-dir 目录下的 *.json，导入后移到 imported/ 子目录
 *
 * 这样外面抓岗位的脚本只需要把文件丢进目录，系统自动入库。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JobService jobService;
    private final ImportBatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.import-dir:import-drop}")
    private String importDir;

    public ImportService(JobService jobService, ImportBatchRepository batchRepository, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void ensureDir() {
        try {
            Files.createDirectories(dropPath());
            Files.createDirectories(dropPath().resolve("imported"));
            Files.createDirectories(dropPath().resolve("failed"));
        } catch (IOException e) {
            log.warn("创建导入目录失败：{}", e.getMessage());
        }
    }

    /** 解析 JSON（支持数组或 {jobs:[...]}）并入库，返回批次记录 */
    public ImportBatch importJson(JsonNode body, String source, String channel) {
        JsonNode array = body.isArray() ? body : body.path("jobs");
        if (!array.isArray()) {
            throw new IllegalArgumentException("请求体需要是数组，或者包含 jobs 数组的对象");
        }
        List<Job> jobs = new ArrayList<>();
        int invalid = 0;
        for (JsonNode node : array) {
            String company = text(node, "company");
            String title = text(node, "title");
            if (company == null || title == null || company.isBlank() || title.isBlank()) {
                invalid++;
                log.warn("导入跳过缺少必填字段的条目：company={}, title={}", company, title);
                continue;
            }
            Job job = new Job();
            job.setCompany(company);
            job.setTitle(title);
            // 地点字段与手动录入统一：优先 rawLocation/location/raw_location，
            // 最后兼容抓取脚本惯用的 city。任一命中即作为原始地点交给 normalizeRegion 拆分。
            job.setRawLocation(firstText(node, "rawLocation", "location", "raw_location", "city"));
            job.setSalary(text(node, "salary"));
            // url 做协议校验：只接受 http/https，javascript: 等协议置空，避免脏数据入库
            String url = text(node, "url");
            if (url != null && !isHttpUrl(url)) {
                log.warn("导入岗位 {} / {} 的 url 协议不被接受，已忽略：{}", company, title, url);
                url = null;
            }
            // 与 Job.url 列长度一致；超长链接（多为一次性 tracking 参数）不入库，避免整批 409
            if (url != null && url.length() > 2048) {
                log.warn("导入岗位 {} / {} 的 url 超过 2048 字符，已忽略：{}", company, title, url);
                url = null;
            }
            job.setUrl(url);
            job.setSkills(text(node, "skills"));
            job.setJdText(text(node, "jdText") != null ? text(node, "jdText") : text(node, "jd"));
            // 抓取脚本可能已经把时长/出勤算好了，有就直接用
            Integer months = number(node, "months");
            Integer days = number(node, "days");
            job.setMonths(months);
            job.setDays(days);
            jobs.add(job);
        }
        int added = jobService.importJobs(jobs);

        ImportBatch batch = new ImportBatch();
        batch.setSource(source);
        batch.setChannel(channel);
        batch.setReceived(jobs.size());
        batch.setAdded(added);
        batch.setSkipped(jobs.size() - added);
        batch.setInvalid(invalid);
        batch.setCreatedAt(LocalDateTime.now());
        return batchRepository.save(batch);
    }

    /** 定时扫描投放目录 */
    @Scheduled(fixedDelayString = "${app.import-scan-ms:300000}", initialDelay = 60000)
    public void scanDropDir() {
        Path dir = dropPath();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .toList();
            for (Path file : files) {
                try {
                    JsonNode body = objectMapper.readTree(Files.readString(file));
                    ImportBatch batch = importJson(body, file.getFileName().toString(), "SCHEDULED");
                    log.info("定时导入 {}：收到 {}，新增 {}", file.getFileName(), batch.getReceived(), batch.getAdded());
                    Path target = dir.resolve("imported").resolve(
                            file.getFileName().toString().replace(".json", "-" + LocalDateTime.now().format(TS) + ".json"));
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log.warn("导入 {} 失败：{}", file.getFileName(), e.getMessage());
                    // 失败文件移入 failed/，避免每 5 分钟重复重试和重复写批次
                    try {
                        Path failed = dir.resolve("failed").resolve(
                                file.getFileName().toString().replace(".json", "-" + LocalDateTime.now().format(TS) + ".json"));
                        Files.move(file, failed, StandardCopyOption.REPLACE_EXISTING);
                        log.info("失败文件已隔离到 {}", failed.getFileName());
                    } catch (IOException moveError) {
                        log.warn("隔离失败文件 {} 时出错：{}", file.getFileName(), moveError.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描导入目录失败：{}", e.getMessage());
        }
    }

    public List<ImportBatch> recentBatches() {
        return batchRepository.findTop20ByOrderByCreatedAtDesc();
    }

    public ImportBatch latestBatch() {
        return batchRepository.findFirstByOrderByCreatedAtDesc().orElse(null);
    }

    private Path dropPath() {
        return Paths.get(importDir).toAbsolutePath();
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 依次取第一个非空的文本字段，兼容多种字段约定 */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String v = text(node, field);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 只接受 http/https 协议；拒绝 javascript:/data:/file: 等危险协议 */
    private boolean isHttpUrl(String url) {
        try {
            String scheme = java.net.URI.create(url.trim()).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Integer number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asInt();
    }
}
