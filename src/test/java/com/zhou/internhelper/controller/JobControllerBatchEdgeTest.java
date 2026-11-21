package com.zhou.internhelper.controller;

import com.zhou.internhelper.repository.JobRepository;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.service.JobService;
import com.zhou.internhelper.service.MetaParser;
import com.zhou.internhelper.service.RegionCatalog;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量端点异常输入契约测试：
 * - N-19 真实缺陷：ids 含 null 时不得整体 500（null key 会使 Jackson 序列化失败）
 * - N-20：batch/contacted 超长话术前置校验，返回 errors 而非落库异常
 * - N-21：非法 ID 用原始字符串作错误键 + 统一中文文案，不透传 Java 原生异常
 *
 * 三条被测路径都在进入 jobService 前被拦截，故 jobService 用真实对象配极简桩即可；
 * 其余依赖（greetingService 等）不被调用，传 null。不用 Mockito（Java 25 兼容问题）。
 */
class JobControllerBatchEdgeTest {

    private JobController controller;

    @BeforeEach
    void setUp() {
        JobRepository jobRepo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(), new Class<?>[]{JobRepository.class},
                (p, m, a) -> "toString".equals(m.getName()) ? "JobRepoStub" : null);
        ApplicationRecordRepository recordRepo = (ApplicationRecordRepository) Proxy.newProxyInstance(
                ApplicationRecordRepository.class.getClassLoader(), new Class<?>[]{ApplicationRecordRepository.class},
                (p, m, a) -> "toString".equals(m.getName()) ? "RecordRepoStub" : null);
        EntityManager em = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(), new Class<?>[]{EntityManager.class},
                (p, m, a) -> null);
        JobService jobService = new JobService(jobRepo, new RegionCatalog(), new MetaParser(), em, recordRepo);
        // jobRepo / recordRepo / regionCatalog / jobRepository 均可复用极简桩
        controller = new JobController(jobService, null, null, null, new RegionCatalog(), jobRepo);
    }

    @Test
    void batchStatus的null元素应写入errors而非抛500() {
        // 只传 null 元素：不进入 jobService，纯验证 null 过滤分支（原 null key 触发 Jackson 500）
        var req = new JobController.BatchStatusRequest(
                java.util.Collections.singletonList(null), "SKIPPED");
        Map<String, Object> r = controller.batchStatus(req);
        assertEquals(0, r.get("changed"));
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.get("errors");
        assertEquals(1, errors.size());
        assertEquals("岗位 ID 不能为空", errors.get("null"));
    }

    @Test
    void batchContacted超长话术应写入errors不落库() {
        String longMsg = "长".repeat(2001);
        Map<String, String> item = Map.of("id", "232", "message", longMsg);
        Map<String, Object> r = controller.batchContacted(List.of(item));
        assertEquals(0, r.get("marked"), "超长话术不应标记为已投");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.get("errors");
        assertEquals("话术不能超过 2000 字", errors.get("232"));
    }

    @Test
    void batchContacted非法ID应用原始字符串作键且不泄露原生异常() {
        Map<String, String> item = Map.of("id", "abc", "message", "x");
        Map<String, Object> r = controller.batchContacted(List.of(item));
        assertEquals(0, r.get("marked"));
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.get("errors");
        assertTrue(errors.containsKey("abc"), "错误键应为原始 ID 'abc'，而非数组索引 0");
        assertEquals("岗位 ID 格式错误", errors.get("abc"));
        assertFalse(errors.get("abc").contains("For input string"));
    }
}
