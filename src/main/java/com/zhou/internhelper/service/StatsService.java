package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final ApplicationRecordRepository recordRepository;
    private final JobRepository jobRepository;

    public StatsService(ApplicationRecordRepository recordRepository, JobRepository jobRepository) {
        this.recordRepository = recordRepository;
        this.jobRepository = jobRepository;
    }

    /** 首页统计：今日/累计/成功率/岗位库状态 */
    public Map<String, Object> overview() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);

        long todaySuccess = recordRepository.countBySuccessTrueAndSentAtBetween(startOfDay, endOfDay);
        long todayTotal = recordRepository.countBySentAtBetween(startOfDay, endOfDay);
        long todayFailed = recordRepository.countFailed(startOfDay, endOfDay);
        long totalSuccess = recordRepository.countBySuccessTrue();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("todaySuccess", todaySuccess);
        map.put("todayFailed", todayFailed);
        map.put("todayTotal", todayTotal);
        map.put("totalSuccess", totalSuccess);
        map.put("successRate", todayTotal == 0 ? 0 : Math.round(todaySuccess * 1000.0 / todayTotal) / 10.0);
        // 软删除的岗位不计入岗位库统计；投递记录仍保留，历史成功率不受影响
        map.put("jobTotal", jobRepository.countByDeletedFalse());
        map.put("jobNew", jobRepository.countByStatusAndDeletedFalse("NEW"));
        map.put("jobContacted", jobRepository.countByStatusAndDeletedFalse("CONTACTED"));
        return map;
    }

    /** 各公司投递次数 Top N */
    public List<Map<String, Object>> topCompanies() {
        return recordRepository.countGroupByCompany().stream()
                .limit(10)
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("company", row[0]);
                    m.put("count", row[1]);
                    return m;
                })
                .toList();
    }

    public List<ApplicationRecord> recent() {
        return recordRepository.findTop50ByOrderBySentAtDesc();
    }
}
