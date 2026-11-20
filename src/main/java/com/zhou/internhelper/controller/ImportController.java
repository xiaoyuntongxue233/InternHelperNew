package com.zhou.internhelper.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhou.internhelper.entity.ImportBatch;
import com.zhou.internhelper.service.ImportService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    /** 手动导入（页面按钮 / curl 都走这里） */
    @PostMapping("/jobs")
    public Map<String, Object> importJobs(@RequestBody JsonNode body,
                                          @RequestParam(defaultValue = "manual") String source) {
        ImportBatch batch = importService.importJson(body, source, "MANUAL");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("batchId", batch.getId());
        map.put("received", batch.getReceived());
        map.put("added", batch.getAdded());
        map.put("skipped", batch.getSkipped());
        map.put("invalid", batch.getInvalid());
        map.put("createdAt", batch.getCreatedAt());
        return map;
    }

    /** 最近的导入批次 */
    @GetMapping("/batches")
    public List<ImportBatch> batches() {
        return importService.recentBatches();
    }

    /** 最后一次同步信息（页面顶部展示） */
    @GetMapping("/latest")
    public Map<String, Object> latest() {
        ImportBatch b = importService.latestBatch();
        Map<String, Object> map = new LinkedHashMap<>();
        if (b == null) {
            map.put("synced", false);
            return map;
        }
        map.put("synced", true);
        map.put("source", b.getSource());
        map.put("channel", b.getChannel());
        map.put("added", b.getAdded());
        map.put("received", b.getReceived());
        map.put("createdAt", b.getCreatedAt());
        return map;
    }
}
