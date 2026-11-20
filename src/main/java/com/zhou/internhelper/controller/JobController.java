package com.zhou.internhelper.controller;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import com.zhou.internhelper.service.GreetingService;
import com.zhou.internhelper.service.JobService;
import com.zhou.internhelper.service.RegionCatalog;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final GreetingService greetingService;
    private final RegionCatalog regionCatalog;
    private final JobRepository jobRepository;
    private final ApplicationRecordRepository recordRepository;

    public JobController(JobService jobService, GreetingService greetingService, RegionCatalog regionCatalog,
                         JobRepository jobRepository, ApplicationRecordRepository recordRepository) {
        this.jobService = jobService;
        this.greetingService = greetingService;
        this.regionCatalog = regionCatalog;
        this.jobRepository = jobRepository;
        this.recordRepository = recordRepository;
    }

    /** 游标/offset 分页 + 多条件筛选 + 排序 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String province,
                                    @RequestParam(required = false) String city,
                                    @RequestParam(required = false) String district,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) Long cursor,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String order,
                                    @RequestParam(defaultValue = "15") int size) {
        return jobService.search(status, province, city, district, category, q, cursor, page, sort, order, size);
    }

    @GetMapping("/{id}")
    public Job detail(@PathVariable Long id) {
        return jobService.get(id);
    }

    @PostMapping
    public Job create(@RequestBody Job job) {
        return jobService.create(job);
    }

    /** 修改岗位 */
    @PutMapping("/{id}")
    public Job update(@PathVariable Long id, @RequestBody Job patch) {
        return jobService.update(id, patch);
    }

    /** 生成打招呼话术（不落库，纯计算） */
    @GetMapping("/{id}/greeting")
    public Map<String, Object> greeting(@PathVariable Long id) {
        Job job = jobService.get(id);
        GreetingService.Greeting g = greetingService.generate(job);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", job.getId());
        map.put("company", job.getCompany());
        map.put("title", job.getTitle());
        map.put("message", g.message());
        map.put("direction", g.direction());
        map.put("months", g.months());
        map.put("days", g.days());
        map.put("length", g.message().length());
        map.put("advice", g.advice());
        map.put("engine", g.engine());
        map.put("templateId", g.templateId());
        map.put("warnings", g.warnings());
        map.put("quotaExceeded", g.quotaExceeded());
        return map;
    }

    /** 记录一次投递（幂等：同岗位当天复用一条记录） */
    @PostMapping("/{id}/contacted")
    @Transactional
    public Map<String, Object> markContacted(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        Job job = jobService.get(id);
        // 投递记录只保存前端已经生成并展示的那份话术，不在此处隐式调用模型，
        // 避免一次「标记已投」额外消耗一次 AI 额度。message 为空是允许的。
        String message = body == null ? null : body.get("message");
        boolean success = body == null || body.get("success") == null || Boolean.parseBoolean(body.get("success"));
        String failReason = body == null ? null : body.get("failReason");

        // 幂等：同一岗位当天已有记录则更新，不重复计数
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        var today = recordRepository.findByJobIdAndSentAtBetweenOrderBySentAtDesc(job.getId(), start, end);
        ApplicationRecord record = today.isEmpty() ? new ApplicationRecord() : today.get(0);
        boolean created = record.getId() == null;

        record.setJobId(job.getId());
        record.setCompany(job.getCompany());
        record.setTitle(job.getTitle());
        // 已有记录且本次没带话术时保留原话术，避免幂等更新把内容清空
        if (message != null || created) {
            record.setMessage(message);
        }
        record.setSuccess(success);
        record.setFailReason(success ? null : failReason);
        if (created) {
            record.setSentAt(LocalDateTime.now());
        }
        record = recordRepository.save(record);

        // 状态保护：失败不把已 CONTACTED 的岗位打回 NEW
        String current = job.getStatus();
        if (success) {
            jobService.updateStatus(job.getId(), "CONTACTED");
        } else if (!"CONTACTED".equals(current)) {
            jobService.updateStatus(job.getId(), "NEW");
        }

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recordId", record.getId());
        map.put("success", success);
        map.put("message", record.getMessage());
        map.put("reused", !created);
        return map;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        jobService.delete(id);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deleted", true);
        map.put("id", id);
        return map;
    }

    @GetMapping("/pending/list")
    public List<Job> pending() {
        return jobService.listPending();
    }

    /** 省 → 市 的筛选项 */
    @GetMapping("/regions/tree")
    public List<Map<String, Object>> regionTree() {
        List<Map<String, Object>> result = new ArrayList<>();
        regionCatalog.tree().forEach((province, cities) -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("province", province);
            node.put("cities", cities);
            result.add(node);
        });
        return result;
    }

    /** 某市下真实存在的区县（从库里查） */
    @GetMapping("/regions/districts")
    public List<String> districts(@RequestParam String province, @RequestParam String city) {
        return jobRepository.findDistricts(province, city);
    }
}
