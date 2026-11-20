package com.zhou.internhelper.service;

import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 搜索参数契约测试：LIKE 通配符转义（N-04）、sort/order 严格校验（选项 C）。
 * 用 JDK 动态代理的 EntityManager/Query 桩捕获 setParameter，验证真正进入 JPQL 的 q 值。
 */
class JobSearchTest {

    private JobService service;
    private Map<String, Object> capturedParams;

    @BeforeEach
    void setUp() {
        capturedParams = new HashMap<>();
        JobRepository repo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                (p, m, a) -> "toString".equals(m.getName()) ? "RepoStub" : null);

        Query queryStub = (Query) Proxy.newProxyInstance(
                Query.class.getClassLoader(),
                new Class<?>[]{Query.class, TypedQuery.class},
                (p, m, a) -> switch (m.getName()) {
                    case "setParameter" -> {
                        if (a.length == 2 && a[0] instanceof String key) capturedParams.put(key, a[1]);
                        yield null;
                    }
                    case "getSingleResult" -> 0L;
                    case "getResultList" -> List.of();
                    case "toString" -> "QueryStub";
                    default -> null;
                });

        EntityManager em = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (p, m, a) -> switch (m.getName()) {
                    case "createQuery" -> queryStub;
                    case "toString" -> "EntityManagerStub";
                    default -> null;
                });

        service = new JobService(repo, new RegionCatalog(), new MetaParser(), em);
    }

    private void searchWithQ(String q) {
        service.search(null, null, null, null, null, q, null, null, null, null, 15);
    }

    @Test
    void 普通关键词不加转义() {
        searchWithQ("java");
        assertEquals("java", capturedParams.get("q"));
    }

    @Test
    void 百分号下划线与感叹号按字面转义() {
        searchWithQ("a%b_c!d");
        assertEquals("a!%b!_c!!d", capturedParams.get("q"),
                "! 必须先转义自身，再转义 % 与 _，顺序不可颠倒");
    }

    @Test
    void 空关键词传null() {
        searchWithQ("   ");
        assertNull(capturedParams.get("q"));
    }

    @Test
    void 非法排序字段抛业务异常() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, null, null, 1, "badField", "asc", 15));
    }

    @Test
    void 非法排序方向抛业务异常() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(null, null, null, null, null, null, null, 1, "id", "sideways", 15));
    }

    @Test
    void 合法排序参数与旧别名仍通过() {
        assertDoesNotThrow(() ->
                service.search(null, null, null, null, null, null, null, 1, "dailyPay", "ASC", 15));
    }
}
