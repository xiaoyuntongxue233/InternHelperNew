package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统计服务契约测试：记录删除的存在性语义、recent 的 limit 收敛。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理的内存版 Repository。
 */
class StatsServiceTest {

    private final Map<Long, ApplicationRecord> store = new LinkedHashMap<>();
    private int lastRequestedPageSize = -1;
    private StatsService service;

    @BeforeEach
    void setUp() {
        ApplicationRecordRepository repo = (ApplicationRecordRepository) Proxy.newProxyInstance(
                ApplicationRecordRepository.class.getClassLoader(),
                new Class<?>[]{ApplicationRecordRepository.class},
                (proxy, method, args) -> handle(method.getName(), args));
        JobRepository jobRepo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "countByDeletedFalse", "countByStatusAndDeletedFalse" -> 0L;
                    case "toString" -> "JobRepoStub";
                    default -> null;
                });
        service = new StatsService(repo, jobRepo);
    }

    private Object handle(String name, Object[] args) {
        switch (name) {
            case "findById":
                return Optional.ofNullable(store.get(args[0]));
            case "delete":
                store.remove(((ApplicationRecord) args[0]).getId());
                return null;
            case "findByOrderBySentAtDesc": {
                Pageable pageable = (Pageable) args[0];
                lastRequestedPageSize = pageable.getPageSize();
                return store.values().stream().limit(pageable.getPageSize()).toList();
            }
            case "findTop50ByOrderBySentAtDesc":
                return List.copyOf(store.values());
            case "toString":
                return "RecordRepoStub";
            default:
                return null;
        }
    }

    private ApplicationRecord record(long id, String company) {
        ApplicationRecord r = new ApplicationRecord();
        r.setId(id);
        r.setCompany(company);
        return r;
    }

    @Test
    void 删除不存在的记录抛业务异常() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteRecord(999999L));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void 删除存在的记录成功() {
        store.put(1L, record(1L, "腾讯"));
        assertDoesNotThrow(() -> service.deleteRecord(1L));
        assertFalse(store.containsKey(1L));
    }

    @Test
    void recent默认50条() {
        service.recent();
        assertEquals(50, lastRequestedPageSize);
    }

    @Test
    void recent的limit收敛到1到200() {
        service.recent(0);
        assertEquals(1, lastRequestedPageSize, "limit 下限收敛到 1");
        service.recent(9999);
        assertEquals(200, lastRequestedPageSize, "limit 上限收敛到 200");
        service.recent(3);
        assertEquals(3, lastRequestedPageSize);
    }
}
