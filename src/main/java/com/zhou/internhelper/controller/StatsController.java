package com.zhou.internhelper.controller;

import com.zhou.internhelper.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/recent")
    public List<?> recent() {
        return statsService.recent();
    }
}
