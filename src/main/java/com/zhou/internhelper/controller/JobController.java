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
        return map;
    }

    /** 记录一次投递（会把岗位状态改成 CONTACTED） */
    @PostMapping("/{id}/contacted")
    @Transactional
    public Map<String, Object> markContacted(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        Job job = jobService.get(id);
        GreetingService.Greeting g = greetingService.generate(job);
        String message = body != null && body.get("message") != null ? body.get("message") : g.message();
        boolean success = body == null || body.get("success") == null || Boolean.parseBoolean(body.get("success"));

        ApplicationRecord record = new ApplicationRecord();
        record.setJobId(job.getId());
        record.setCompany(job.getCompany());
        record.setTitle(job.getTitle());
        record.setMessage(message);
        record.setSuccess(success);
        if (!success && body != null) {
            record.setFailReason(body.get("failReason"));
        }
        recordRepository.save(record);
        jobService.updateStatus(job.getId(), success ? "CONTACTED" : "NEW");

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("recordId", record.getId());
        map.put("success", success);
        map.put("message", message);
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
