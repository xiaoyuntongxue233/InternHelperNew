package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhou.internhelper.entity.ImportBatch;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ImportBatchRepository;
import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导入服务契约测试：地点字段多约定兼容（N-08）、url 协议校验与 invalid 计数（N-11）。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理桩 + 真实 JobService。
 */
class ImportServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Job> store = new LinkedHashMap<>();
    private List<Job> capturedJobs;
    private ImportBatch savedBatch;
    private ImportService service;

    @BeforeEach
    void setUp() {
        JobRepository jobRepo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        Job job = (Job) args[0];
                        store.put(job.getId() == null ? (long) (store.size() + 1) : job.getId(), job);
                        yield job;
                    }
                    case "findByTitleHashAndDeletedFalse", "findByCompanyAndTitleAndDeletedFalse" ->
                            java.util.Optional.empty();
                    case "existsByCompanyAndTitleAndDeletedFalse" -> false;
                    case "findByProvinceIsNullAndDeletedFalse", "findByMonthsIsNullAndDeletedFalse",
                            "findByDailyMinIsNullAndDeletedFalse", "findByDeletedFalse" -> List.of();
                    case "countByStatusAndDeletedFalse" -> 0L;
                    case "toString" -> "JobRepoStub";
                    default -> null;
                });
        EntityManager em = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (p, m, a) -> "toString".equals(m.getName()) ? "EmStub" : null);
        JobService jobService = new JobService(jobRepo, new RegionCatalog(), new MetaParser(), em,
                (com.zhou.internhelper.repository.ApplicationRecordRepository) Proxy.newProxyInstance(
                        com.zhou.internhelper.repository.ApplicationRecordRepository.class.getClassLoader(),
                        new Class<?>[]{com.zhou.internhelper.repository.ApplicationRecordRepository.class},
                        (p, m, a) -> "toString".equals(m.getName()) ? "RecordRepoStub" : null));

        ImportBatchRepository batchRepo = (ImportBatchRepository) Proxy.newProxyInstance(
                ImportBatchRepository.class.getClassLoader(),
                new Class<?>[]{ImportBatchRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        savedBatch = (ImportBatch) args[0];
                        savedBatch.setId(1L);
                        yield savedBatch;
                    }
                    case "toString" -> "BatchRepoStub";
                    default -> null;
                });

        service = new ImportService(jobService, batchRepo, mapper);
        capturedJobs = new ArrayList<>();
        store.clear();
        savedBatch = null;
    }

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    /** 从内存仓库取出本批次落库的 Job（按公司名匹配，避免依赖 id 生成顺序） */
    private Job byCompany(String company) {
        return store.values().stream().filter(j -> company.equals(j.getCompany())).findFirst().orElse(null);
    }

    @Test
    void 地点字段多种约定都能识别() throws Exception {
        JsonNode body = json("""
                [
                  {"company":"A","title":"t1","rawLocation":"广州天河区"},
                  {"company":"B","title":"t2","location":"北京市海淀区"},
                  {"company":"C","title":"t3","raw_location":"深圳南山"},
                  {"company":"D","title":"t4","city":"上海"}
                ]
                """);
        service.importJson(body, "test", "MANUAL");

        assertEquals(4, store.size());
        assertEquals("广州天河区", byCompany("A").getRawLocation());
        assertEquals("北京市海淀区", byCompany("B").getRawLocation());
        assertEquals("深圳南山", byCompany("C").getRawLocation());
        assertEquals("上海", byCompany("D").getRawLocation());
    }

    @Test
    void rawLocation优先于city() throws Exception {
        JsonNode body = json("[{\"company\":\"A\",\"title\":\"t\",\"rawLocation\":\"广州天河\",\"city\":\"北京\"}]");
        service.importJson(body, "test", "MANUAL");
        assertEquals("广州天河", byCompany("A").getRawLocation());
    }

    @Test
    void javascript协议url被拒绝() throws Exception {
        JsonNode body = json("""
                [
                  {"company":"A","title":"t1","url":"javascript:alert(1)"},
                  {"company":"B","title":"t2","url":"https://example.com/job"},
                  {"company":"C","title":"t3","url":"data:text/html,x"}
                ]
                """);
        service.importJson(body, "test", "MANUAL");

        assertNull(byCompany("A").getUrl(), "javascript: 应被拒绝");
        assertEquals("https://example.com/job", byCompany("B").getUrl());
        assertNull(byCompany("C").getUrl(), "data: 应被拒绝");
    }

    @Test
    void 缺少必填字段计入invalid且不影响正常条目() throws Exception {
        JsonNode body = json("""
                [
                  {"company":"A","title":"t1"},
                  {"company":"","title":"t2"},
                  {"title":"缺公司"},
                  {"company":"B"}
                ]
                """);
        service.importJson(body, "test", "MANUAL");

        assertEquals(1, store.size(), "只有一条合法");
        assertEquals(3, savedBatch.getInvalid(), "三条缺必填字段计入 invalid");
        assertEquals(1, savedBatch.getReceived());
    }
}
