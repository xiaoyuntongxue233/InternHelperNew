package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.GreetingVersion;
import com.zhou.internhelper.repository.GreetingVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 话术版本留痕契约测试：单岗位最多保留 20 条，超出删最旧。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理的内存版 Repository。
 */
class GreetingVersionServiceTest {

    private final List<GreetingVersion> store = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    private GreetingVersionService service;

    @BeforeEach
    void setUp() {
        GreetingVersionRepository repo = (GreetingVersionRepository) Proxy.newProxyInstance(
                GreetingVersionRepository.class.getClassLoader(),
                new Class<?>[]{GreetingVersionRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        GreetingVersion v = (GreetingVersion) a[0];
                        if (v.getId() == null) v.setId(seq.incrementAndGet());
                        store.add(v);
                        yield v;
                    }
                    case "findByJobIdOrderByCreatedAtAsc" -> {
                        Long jobId = (Long) a[0];
                        yield store.stream().filter(v -> jobId.equals(v.getJobId()))
                                .sorted(Comparator.comparing(GreetingVersion::getCreatedAt)
                                        .thenComparing(GreetingVersion::getId))
                                .toList();
                    }
                    case "findByJobIdOrderByCreatedAtDesc" -> {
                        Long jobId = (Long) a[0];
                        yield store.stream().filter(v -> jobId.equals(v.getJobId()))
                                .sorted(Comparator.comparing(GreetingVersion::getCreatedAt).reversed())
                                .toList();
                    }
                    case "delete" -> {
                        store.remove((GreetingVersion) a[0]);
                        yield null;
                    }
                    case "toString" -> "VersionRepoStub";
                    default -> null;
                });
        service = new GreetingVersionService(repo);
    }

    private GreetingVersion version(long jobId, int n) {
        GreetingVersion v = new GreetingVersion();
        v.setJobId(jobId);
        v.setMessage("话术-" + n);
        v.setCreatedAt(LocalDateTime.now().plusSeconds(n));
        return v;
    }

    @Test
    void 追加25条只保留最近20条() {
        for (int i = 0; i < 25; i++) {
            service.save(version(1L, i));
        }
        List<GreetingVersion> list = service.list(1L);
        assertEquals(20, list.size(), "单岗位最多 20 条");
        assertEquals("话术-24", list.get(0).getMessage(), "最新的排最前");
        assertFalse(store.stream().anyMatch(v -> "话术-0".equals(v.getMessage())), "最旧的应被删除");
        assertFalse(store.stream().anyMatch(v -> "话术-4".equals(v.getMessage())), "前 5 条都应被裁剪");
    }

    @Test
    void 不同岗位互不干扰() {
        for (int i = 0; i < 5; i++) service.save(version(1L, i));
        service.save(version(2L, 0));
        assertEquals(5, service.list(1L).size());
        assertEquals(1, service.list(2L).size());
    }

    @Test
    void 未超限不删除() {
        for (int i = 0; i < 3; i++) service.save(version(1L, i));
        assertEquals(3, service.list(1L).size());
    }
}
