package com.zhou.internhelper.controller;

import com.zhou.internhelper.service.StatsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("overview", statsService.overview());
        map.put("topCompanies", statsService.topCompanies());
        return map;
    }

    /** 最近投递记录：limit 默认 50，最多 200（由 service 收敛） */
    @GetMapping("/recent")
    public List<?> recent(@RequestParam(defaultValue = "50") int limit) {
        return statsService.recent(limit);
    }

    /** 删除单条投递记录：用于清理测试残留；AI 额度账本不可变，不在此列 */
    @DeleteMapping("/recent/{id}")
    public Map<String, Object> deleteRecord(@PathVariable Long id) {
        statsService.deleteRecord(id);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deleted", true);
        map.put("id", id);
        return map;
    }
}
