package com.zhou.internhelper.controller;

import com.zhou.internhelper.entity.GreetingVersion;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.JobRepository;
import com.zhou.internhelper.service.GreetingService;
import com.zhou.internhelper.service.GreetingVersionService;
import com.zhou.internhelper.service.JobService;
import com.zhou.internhelper.service.RegionCatalog;
import com.zhou.internhelper.service.SettingsService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    /** 批量生成话术的条数上限：AI/HYBRID 受额度约束较严，RULE 是纯计算可放宽 */
    private static final int BATCH_LIMIT_AI = 10;
    private static final int BATCH_LIMIT_RULE = 30;
    private static final int BATCH_LIMIT_COMMON = 20;
    private static final java.util.Set<String> VALID_STATUSES =
            java.util.Set.of("NEW", "CONTACTED", "SKIPPED");

    private final JobService jobService;
    private final GreetingService greetingService;
    private final GreetingVersionService versionService;
    private final SettingsService settingsService;
    private final RegionCatalog regionCatalog;
    private final JobRepository jobRepository;

    public JobController(JobService jobService, GreetingService greetingService,
                         GreetingVersionService versionService, SettingsService settingsService,
                         RegionCatalog regionCatalog, JobRepository jobRepository) {
        this.jobService = jobService;
        this.greetingService = greetingService;
        this.versionService = versionService;
        this.settingsService = settingsService;
        this.regionCatalog = regionCatalog;
        this.jobRepository = jobRepository;
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

    /** 生成打招呼话术：生成即留痕一条草稿版本（不额外消耗 AI 额度） */
    @GetMapping("/{id}/greeting")
    public Map<String, Object> greeting(@PathVariable Long id) {
        Job job = jobService.get(id);
        GreetingService.Greeting g = greetingService.generate(job);
        appendVersion(job.getId(), g, "GENERATE");
        return greetingVo(job, g);
    }

    /** 话术草稿历史（按时间倒序，单岗位最多 20 条） */
    @GetMapping("/{id}/greetings")
    public List<GreetingVersion> greetingHistory(@PathVariable Long id) {
        jobService.get(id); // 软删岗位视为不存在
        return versionService.list(id);
    }

    /** 手动编辑保存为一条新版本 */
    @PutMapping("/{id}/greeting")
    public Map<String, Object> saveGreeting(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Job job = jobService.get(id);
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("话术内容不能为空");
        }
        if (message.length() > 2000) {
            throw new IllegalArgumentException("话术不能超过 2000 字");
        }
        GreetingVersion v = new GreetingVersion();
        v.setJobId(id);
        v.setMessage(message);
        v.setEngine("EDIT");
        v.setSource("EDIT");
        versionService.save(v);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("saved", true);
        map.put("length", message.length());
        return map;
    }

    /** 记录一次投递（幂等：同岗位当天复用一条记录） */
    @PostMapping("/{id}/contacted")
    public Map<String, Object> markContacted(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        boolean success = body == null || body.get("success") == null || Boolean.parseBoolean(body.get("success"));
        String failReason = body == null ? null : body.get("failReason");
        return jobService.markContacted(id, message, success, failReason);
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

    // ==================== 批量操作 ====================

    /** 批量生成话术：AI/HYBRID ≤10 条，RULE ≤30 条；复用既有额度预留，不加事务（外部 IO） */
    @PostMapping("/batch/greeting")
    public Map<String, Object> batchGreeting(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body == null ? null : body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请提供 ids");
        }
        String engine = settingsService.snapshot().engine();
        boolean ai = "AI".equalsIgnoreCase(engine) || "HYBRID".equalsIgnoreCase(engine);
        int limit = ai ? BATCH_LIMIT_AI : BATCH_LIMIT_RULE;
        if (ids.size() > limit) {
            throw new IllegalArgumentException("批量生成" + (ai ? "在 AI 模式" : "在 RULE 模式")
                    + "最多 " + limit + " 条，当前 " + ids.size() + " 条");
        }

        Map<Long, Object> results = new LinkedHashMap<>();
        Map<Long, String> errors = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                Job job = jobService.get(id); // 软删岗位视为不存在
                GreetingService.Greeting g = greetingService.generate(job);
                appendVersion(job.getId(), g, "GENERATE");
                results.put(id, greetingVo(job, g));
            } catch (Exception e) {
                errors.put(id, e.getMessage() == null ? "生成失败" : e.getMessage());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("results", results);
        map.put("errors", errors);
        map.put("ai", ai);
        return map;
    }

    /** 批量改状态：状态走白名单，逐条走 updateStatus（软删岗位视为不存在） */
    @PostMapping("/batch/status")
    public Map<String, Object> batchStatus(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = body == null ? null : (List<Long>) body.get("ids");
        String status = body == null ? null : (String) body.get("status");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请提供 ids");
        }
        if (ids.size() > BATCH_LIMIT_COMMON) {
            throw new IllegalArgumentException("单次批量最多 " + BATCH_LIMIT_COMMON + " 条");
        }
        if (!VALID_STATUSES.contains(status)) {
            throw new IllegalArgumentException("岗位状态只能是 NEW / CONTACTED / SKIPPED");
        }
        int changed = 0;
        Map<Long, String> errors = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                jobService.updateStatus(id, status);
                changed++;
            } catch (Exception e) {
                errors.put(id, e.getMessage() == null ? "更新失败" : e.getMessage());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changed", changed);
        map.put("errors", errors);
        return map;
    }

    /** 批量软删：保留投递记录引用，单条失败不中断整批 */
    @PostMapping("/batch/delete")
    public Map<String, Object> batchDelete(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body == null ? null : body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请提供 ids");
        }
        if (ids.size() > BATCH_LIMIT_COMMON) {
            throw new IllegalArgumentException("单次批量最多 " + BATCH_LIMIT_COMMON + " 条");
        }
        int deleted = 0;
        Map<Long, String> errors = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                jobService.delete(id);
                deleted++;
            } catch (Exception e) {
                errors.put(id, e.getMessage() == null ? "删除失败" : e.getMessage());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deleted", deleted);
        map.put("errors", errors);
        return map;
    }

    /** 批量标记已投：复用单条 markContacted（幂等 + 状态保护） */
    @Transactional
    @PostMapping("/batch/contacted")
    public Map<String, Object> batchContacted(@RequestBody List<Map<String, String>> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("请提供 items");
        }
        if (items.size() > BATCH_LIMIT_COMMON) {
            throw new IllegalArgumentException("单次批量最多 " + BATCH_LIMIT_COMMON + " 条");
        }
        int marked = 0;
        Map<Integer, String> errors = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, String> item = items.get(i);
            try {
                Long id = Long.valueOf(item.get("id"));
                String message = item.get("message");
                boolean success = item.get("success") == null || Boolean.parseBoolean(item.get("success"));
                jobService.markContacted(id, message, success, item.get("failReason"));
                marked++;
            } catch (Exception e) {
                errors.put(i, e.getMessage() == null ? "记录失败" : e.getMessage());
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("marked", marked);
        map.put("errors", errors);
        return map;
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

    // ==================== 内部辅助 ====================

    private void appendVersion(Long jobId, GreetingService.Greeting g, String source) {
        GreetingVersion v = new GreetingVersion();
        v.setJobId(jobId);
        v.setMessage(g.message());
        v.setEngine(g.engine());
        v.setTemplateId(g.templateId());
        v.setDirection(g.direction());
        v.setMonths(g.months());
        v.setDays(g.days());
        v.setSource(source);
        versionService.save(v);
    }

    private Map<String, Object> greetingVo(Job job, GreetingService.Greeting g) {
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
}
