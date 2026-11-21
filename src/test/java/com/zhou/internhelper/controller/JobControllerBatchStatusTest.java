package com.zhou.internhelper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归 N-16：批量改状态请求体必须用类型化 DTO 接收。
 * 若退回 Map&lt;String,Object&gt;，Jackson 会把 JSON 整数推断为 Integer，
 * 遍历 for(Long id : ids) 时抛 ClassCastException → 500。
 */
class JobControllerBatchStatusTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void 批量状态请求体的ids应反序列化为Long而非Integer() throws Exception {
        String json = "{\"ids\":[489,490],\"status\":\"SKIPPED\"}";
        JobController.BatchStatusRequest req =
                om.readValue(json, JobController.BatchStatusRequest.class);

        assertEquals("SKIPPED", req.status());
        List<Long> ids = req.ids();
        assertEquals(2, ids.size());
        // 关键断言：元素运行时类型是 Long，for(Long id : ids) 不再触发 checkcast 异常
        assertAll(
                () -> assertEquals(Long.class, ids.get(0).getClass()),
                () -> assertEquals(Long.class, ids.get(1).getClass()),
                () -> {
                    long sum = 0;
                    for (Long id : ids) sum += id;   // 曾在这里抛 ClassCastException
                    assertEquals(489L + 490L, sum);
                }
        );
    }

    @Test
    void 空请求体反序列化后ids为null由业务校验兜底() throws Exception {
        JobController.BatchStatusRequest req =
                om.readValue("{\"status\":\"NEW\"}", JobController.BatchStatusRequest.class);
        assertNull(req.ids());
        assertEquals("NEW", req.status());
    }
}
