package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 把「其实已经投过、但库里还标着未联系」的岗位同步成已投递。
 *
 * 判据（两条独立证据，命中任意一条即视为已投）：
 *   1. 打招呼脚本的发送日志 out/send_log.jsonl 里，该公司有 status=sent 的记录；
 *   2. 投递记录表 application_record 里已经有这个公司。
 *
 * 只补状态和缺失的投递记录，不改岗位其他字段，也不删除任何东西。
 */
@Service
public class ContactSyncService {

    private static final Logger log = LoggerFactory.getLogger(ContactSyncService.class);

    private final BossScriptLocator locator;
    private final JobRepository jobRepository;
    private final ApplicationRecordRepository recordRepository;
    private final GreetingService greetingService;

    public ContactSyncService(BossScriptLocator locator,
                              JobRepository jobRepository,
                              ApplicationRecordRepository recordRepository,
                              GreetingService greetingService) {
        this.locator = locator;
        this.jobRepository = jobRepository;
        this.recordRepository = recordRepository;
        this.greetingService = greetingService;
    }

    /** 日志里一次成功发送的摘要 */
    private record SentInfo(String rawCompany, String lastTime, String message) {
    }

    @Transactional
    public Map<String, Object> sync() {
        Map<String, SentInfo> sent = readSentFromLog();
        List<ApplicationRecord> records = recordRepository.findAll();

        Set<String> recordedCompanies = new HashSet<>();
        Set<Long> recordedJobIds = new HashSet<>();
        for (ApplicationRecord r : records) {
            String k = BossScriptLocator.normCompany(r.getCompany());
            if (!k.isEmpty()) recordedCompanies.add(k);
            if (r.getJobId() != null) recordedJobIds.add(r.getJobId());
        }

        List<Job> all = jobRepository.findAll();
        int updated = 0, alreadyContacted = 0, recordsCreated = 0, evidenceOnly = 0;
        List<Map<String, Object>> changed = new ArrayList<>();

        for (Job job : all) {
            String key = BossScriptLocator.normCompany(job.getCompany());
            if (key.isEmpty()) continue;

            SentInfo info = sent.get(key);
            boolean fromLog = info != null;
            boolean fromRecord = recordedCompanies.contains(key);
            if (!fromLog && !fromRecord) continue;

            if ("CONTACTED".equals(job.getStatus())) {
                alreadyContacted++;
                // 状态对了但缺投递记录时补一条，统计才不会漏
                if (!recordedJobIds.contains(job.getId())) {
                    saveRecord(job, info, recordedJobIds);
                    recordsCreated++;
                }
                continue;
            }

            job.setStatus("CONTACTED");
            jobRepository.save(job);
            updated++;

            if (!recordedJobIds.contains(job.getId())) {
                saveRecord(job, info, recordedJobIds);
                recordsCreated++;
            }

            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", job.getId());
            c.put("company", job.getCompany());
            c.put("title", job.getTitle());
            c.put("evidence", fromLog ? "send_log（" + info.lastTime + "）" : "application_record");
            changed.add(c);

            if (fromLog) evidenceOnly++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedJobs", all.size());
        result.put("logCompanies", sent.size());
        result.put("recordedCompanies", recordedCompanies.size());
        result.put("updated", updated);
        result.put("alreadyContacted", alreadyContacted);
        result.put("recordsCreated", recordsCreated);
        result.put("changed", changed);
        result.put("logFound", Files.exists(locator.sendLogPath()));
        log.info("同步已投状态完成：扫描 {} 个岗位，改状态 {} 个，补记录 {} 条", all.size(), updated, recordsCreated);
        return result;
    }

    private void saveRecord(Job job, SentInfo info, Set<Long> recordedJobIds) {
        ApplicationRecord rec = new ApplicationRecord();
        rec.setJobId(job.getId());
        rec.setCompany(job.getCompany());
        rec.setTitle(job.getTitle());
        String msg = info != null && info.message() != null && !info.message().isBlank()
                ? info.message()
                : safeGreeting(job);
        rec.setMessage(msg);
        rec.setSuccess(true);
        // 投递时间取日志里的真实时间：否则同步出来的记录会全部记成「今天」，
        // 把「今日已投」这个统计虚增。
        LocalDateTime sentAt = info == null ? null : parseIso(info.lastTime());
        rec.setSentAt(sentAt != null ? sentAt : LocalDateTime.now());
        recordRepository.save(rec);
        recordedJobIds.add(job.getId());
    }

    private static LocalDateTime parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String safeGreeting(Job job) {
        try {
            return greetingService.generate(job).message();
        } catch (Exception e) {
            return "";
        }
    }

    /** 读 out/send_log.jsonl，只取 status=sent 的记录 */
    private Map<String, SentInfo> readSentFromLog() {
        Map<String, SentInfo> map = new LinkedHashMap<>();
        Path path = locator.sendLogPath();
        if (!Files.exists(path)) {
            log.warn("发送日志不存在：{}", path);
            return map;
        }
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (raw.isBlank()) continue;
                Map<String, Object> rec;
                try {
                    rec = parseFlatJson(raw);
                } catch (Exception e) {
                    continue;
                }
                Object statusObj = rec.get("status");
                String status = statusObj == null ? "" : String.valueOf(statusObj);
                if (status.isEmpty()) {
                    status = Boolean.TRUE.equals(rec.get("ok")) ? "sent" : "failed";
                }
                // 两种记录都算「已联系过」的证据：
                //   1) status=sent  —— 我们真发过话术
                //   2) reason=boss-already-chatted —— 打开岗位页时 BOSS 显示「继续沟通」，
                //      说明之前（可能是手动）已经联系过，这条比数据库状态更可信
                boolean sentOk = "sent".equals(status);
                boolean alreadyChatted = "boss-already-chatted".equals(rec.get("reason") == null
                        ? "" : String.valueOf(rec.get("reason")));
                if (!sentOk && !alreadyChatted) continue;

                String company = rec.get("company") == null ? "" : String.valueOf(rec.get("company"));
                String key = BossScriptLocator.normCompany(company);
                if (key.isEmpty()) continue;

                String time = rec.get("time") == null ? "" : String.valueOf(rec.get("time"));
                String msg = rec.get("message") == null ? "" : String.valueOf(rec.get("message"));
                SentInfo prev = map.get(key);
                // 同一公司多次成功时，保留最后一次的时间、最后一次带的话术
                if (prev == null || time.compareTo(prev.lastTime()) >= 0) {
                    map.put(key, new SentInfo(company, time, msg.isBlank() && prev != null ? prev.message() : msg));
                }
            }
        } catch (IOException e) {
            log.warn("读发送日志失败：{}", e.getMessage());
        }
        return map;
    }
    /** 极简扁平 JSON 解析：只处理 send_log.jsonl 的一层字段 */
    private static java.util.Map<String, Object> parseFlatJson(String json) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("\"(\\w+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|null|-?\\d+)")
                .matcher(json);
        while (mt.find()) {
            String k = mt.group(1);
            String v = mt.group(2);
            if (v.startsWith("\"")) {
                String s = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                m.put(k, s);
            } else if ("true".equals(v)) {
                m.put(k, Boolean.TRUE);
            } else if ("false".equals(v)) {
                m.put(k, Boolean.FALSE);
            } else if ("null".equals(v)) {
                m.put(k, null);
            } else {
                try { m.put(k, Long.parseLong(v)); } catch (NumberFormatException e) { m.put(k, v); }
            }
        }
        return m;
    }
}
