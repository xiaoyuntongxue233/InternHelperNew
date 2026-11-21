package com.zhou.internhelper.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网页端按网址取数的任务契约测试：URL 校验、异步状态、成功/失败与并发上限。
 * 用子类替换 runScript，不依赖本机 Node / Edge 环境。
 */
class UrlExtractionServiceTest {

    private static class StubService extends UrlExtractionService {
        private final Map<String, Object> result;
        private final String failure;
        private final long sleepMs;
        private int calls;

        StubService(Map<String, Object> result) {
            this(result, null, 0);
        }

        StubService(String failure) {
            this(Map.of(), failure, 0);
        }

        StubService(Map<String, Object> result, String failure, long sleepMs) {
            super(12231, "");
            this.result = result;
            this.failure = failure;
            this.sleepMs = sleepMs;
        }

        @Override
        protected Map<String, Object> runScript(String url, Path out, Path shot, Path logFile) throws Exception {
            calls++;
            if (sleepMs > 0) Thread.sleep(sleepMs);
            if (failure != null) throw new IllegalStateException(failure);
            return result;
        }
    }

    private static Map<String, Object> okDraft() {
        return Map.of("draft", Map.of(
                "company", "测试科技有限公司",
                "title", "Java后端开发实习生",
                "warnings", List.of("示例提示")
        ));
    }

    private static UrlExtractionService.StatusResult waitFor(StubService service, String jobId) {
        for (int i = 0; i < 100; i++) {
            UrlExtractionService.StatusResult status = service.status(jobId);
            if (!"RUNNING".equals(status.status())) return status;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return service.status(jobId);
    }

    @Test
    void 非法网址被拒绝() {
        StubService service = new StubService(okDraft());
        assertThrows(IllegalArgumentException.class, () -> service.submit(null));
        assertThrows(IllegalArgumentException.class, () -> service.submit("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> service.submit("不是网址"));
        assertEquals(0, service.calls, "非法输入不应启动脚本");
    }

    @Test
    void 成功任务返回草稿与提示() {
        StubService service = new StubService(okDraft());
        var submitted = service.submit("https://www.zhipin.com/job_detail/x.html");
        assertNotNull(submitted.jobId());
        assertEquals("RUNNING", submitted.status());

        var status = waitFor(service, submitted.jobId());
        assertEquals("SUCCESS", status.status());
        assertEquals("测试科技有限公司", status.draft().get("company"));
        assertEquals("Java后端开发实习生", status.draft().get("title"));
        assertTrue(((List<?>) status.draft().get("warnings")).contains("示例提示"));
        assertEquals(1, service.calls);
    }

    @Test
    void 脚本失败写入任务状态而不抛给前端() {
        StubService service = new StubService("脚本失败：未登录");
        String jobId = service.submit("https://www.zhipin.com/job_detail/y.html").jobId();
        var status = waitFor(service, jobId);

        assertEquals("FAILED", status.status());
        assertTrue(status.message().contains("脚本失败"));
        assertNull(status.draft());
    }

    @Test
    void 未知任务编号返回明确错误() {
        StubService service = new StubService(okDraft());
        assertThrows(IllegalArgumentException.class, () -> service.status("not-exist"));
    }

    @Test
    void 同时最多两个取数任务() {
        StubService service = new StubService(okDraft(), null, 400);
        service.submit("https://www.zhipin.com/job_detail/a.html");
        service.submit("https://www.zhipin.com/job_detail/b.html");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.submit("https://www.zhipin.com/job_detail/c.html"));
        assertTrue(e.getMessage().contains("最多处理 2"));
    }
}
