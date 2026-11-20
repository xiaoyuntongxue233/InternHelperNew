package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 投递记录契约测试：markContacted 的幂等、状态保护（单条/批量共用同一逻辑）。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理的内存版 Repository。
 */
class JobServiceContactTest {

    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private final List<ApplicationRecord> records = new ArrayList<>();
    private final AtomicLong jobSeq = new AtomicLong(0);
    private final AtomicLong recSeq = new AtomicLong(0);
    private JobService service;

    @BeforeEach
    void setUp() {
        JobRepository jobRepo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save", "saveAndFlush" -> {
                        Job j = (Job) a[0];
                        if (j.getId() == null) j.setId(jobSeq.incrementAndGet());
                        jobs.put(j.getId(), j);
                        yield j;
                    }
                    case "findByIdAndDeletedFalse" -> {
                        Job j = jobs.get(a[0]);
                        yield j != null && !j.isDeleted() ? Optional.of(j) : Optional.empty();
                    }
                    case "findByTitleHashAndDeletedFalse", "findByCompanyAndTitleAndDeletedFalse" ->
                            Optional.empty();
                    case "existsByCompanyAndTitleAndDeletedFalse" -> false;
                    case "toString" -> "JobRepoStub";
                    default -> null;
                });

        ApplicationRecordRepository recRepo = (ApplicationRecordRepository) Proxy.newProxyInstance(
                ApplicationRecordRepository.class.getClassLoader(),
                new Class<?>[]{ApplicationRecordRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findByJobIdAndSentAtBetweenOrderBySentAtDesc" -> {
                        Long jobId = (Long) a[0];
                        yield records.stream()
                                .filter(r -> jobId.equals(r.getJobId()))
                                .sorted((x, y) -> y.getSentAt().compareTo(x.getSentAt()))
                                .toList();
                    }
                    case "save" -> {
                        ApplicationRecord r = (ApplicationRecord) a[0];
                        if (r.getId() == null) {
                            r.setId(recSeq.incrementAndGet());
                            records.add(r);
                        }
                        yield r;
                    }
                    case "toString" -> "RecRepoStub";
                    default -> null;
                });

        EntityManager em = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (p, m, a) -> "toString".equals(m.getName()) ? "EmStub" : null);

        service = new JobService(jobRepo, new RegionCatalog(), new MetaParser(), em, recRepo);
    }

    private Job newJob(String status) {
        Job j = new Job();
        j.setCompany("腾讯");
        j.setTitle("岗位");
        j.setStatus(status);
        return service.create(j);
    }

    @Test
    void 成功投递写入记录并置已投递() {
        Job j = newJob("NEW");
        Map<String, Object> r = service.markContacted(j.getId(), "您好", true, null);

        assertEquals(1, records.size());
        assertEquals("您好", records.get(0).getMessage());
        assertEquals("CONTACTED", jobs.get(j.getId()).getStatus());
        assertEquals(Boolean.TRUE, r.get("success"));
        assertEquals(Boolean.FALSE, r.get("reused"));
    }

    @Test
    void 同日重复投递复用记录不重复计数() {
        Job j = newJob("NEW");
        service.markContacted(j.getId(), "第一版", true, null);
        Map<String, Object> second = service.markContacted(j.getId(), "第二版", true, null);

        assertEquals(1, records.size(), "同岗位当天只有一条记录");
        assertEquals("第二版", records.get(0).getMessage(), "幂等更新话术");
        assertEquals(Boolean.TRUE, second.get("reused"));
    }

    @Test
    void 失败投递不把已跳过打回未投递() {
        Job j = newJob("SKIPPED");
        service.markContacted(j.getId(), "", false, "未回复");
        assertEquals("SKIPPED", jobs.get(j.getId()).getStatus(), "失败不得打回 NEW");
    }

    @Test
    void 无话术时不覆盖已有记录内容() {
        Job j = newJob("NEW");
        service.markContacted(j.getId(), "原话术", true, null);
        // 新增一条记录场景由 created 分支覆盖，这里验证幂等时 message=null 保留原内容
        service.markContacted(j.getId(), null, true, null);
        assertEquals("原话术", records.get(0).getMessage());
    }

    @Test
    void 已投递状态回退到未投递不删记录() {
        Job j = newJob("CONTACTED");
        service.markContacted(j.getId(), "已发话术", true, null);
        service.updateStatus(j.getId(), "NEW"); // 状态下拉设为未投递
        assertEquals("NEW", jobs.get(j.getId()).getStatus());
        assertEquals(1, records.size(), "状态回退不影响历史投递记录");
    }
}
