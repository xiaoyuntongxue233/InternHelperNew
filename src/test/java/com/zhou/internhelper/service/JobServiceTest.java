package com.zhou.internhelper.service;

import com.zhou.internhelper.config.CandidateProperties;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 岗位服务契约测试：编辑改名查重、软删除、哈希去重。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理的内存版 Repository。
 */
class JobServiceTest {

    private final Map<Long, Job> store = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong(0);
    private JobService service;
    /** 打开后 saveAndFlush 模拟并发唯一键冲突 */
    private boolean simulateUniqueConflict = false;

    @BeforeEach
    void setUp() {
        JobRepository repo = (JobRepository) Proxy.newProxyInstance(
                JobRepository.class.getClassLoader(),
                new Class<?>[]{JobRepository.class},
                (proxy, method, args) -> handle(method.getName(), args));
        service = new JobService(repo, new RegionCatalog(), new MetaParser(),
                (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                        new Class<?>[]{EntityManager.class}, (p, m, a) -> null),
                (com.zhou.internhelper.repository.ApplicationRecordRepository) Proxy.newProxyInstance(
                        com.zhou.internhelper.repository.ApplicationRecordRepository.class.getClassLoader(),
                        new Class<?>[]{com.zhou.internhelper.repository.ApplicationRecordRepository.class},
                        (p, m, a) -> "toString".equals(m.getName()) ? "RecordRepoStub" : null));
    }

    private Object handle(String name, Object[] args) {
        switch (name) {
            case "save":
            case "saveAndFlush": {
                if ("saveAndFlush".equals(name) && simulateUniqueConflict) {
                    throw new org.springframework.dao.DataIntegrityViolationException("uk_job_title_hash duplicate");
                }
                Job job = (Job) args[0];
                if (job.getId() == null) job.setId(seq.incrementAndGet());
                store.put(job.getId(), job);
                return job;
            }
            case "saveAll": {
                @SuppressWarnings("unchecked")
                Iterable<Job> jobs = (Iterable<Job>) args[0];
                jobs.forEach(j -> {
                    if (j.getId() == null) j.setId(seq.incrementAndGet());
                    store.put(j.getId(), j);
                });
                return jobs;
            }
            case "findById":
                return Optional.ofNullable(store.get(args[0]));
            case "findByIdAndDeletedFalse": {
                Job job = store.get(args[0]);
                return job != null && !job.isDeleted() ? Optional.of(job) : Optional.empty();
            }
            case "findByTitleHashAndDeletedFalse": {
                String hash = (String) args[0];
                return store.values().stream()
                        .filter(j -> hash.equals(j.getTitleHash()) && !j.isDeleted())
                        .findFirst();
            }
            case "findByDeletedTrue":
                return store.values().stream().filter(Job::isDeleted).toList();
            case "existsByCompanyAndTitleAndDeletedFalse": {
                String company = (String) args[0];
                String title = (String) args[1];
                return store.values().stream().anyMatch(j -> !j.isDeleted()
                        && company.equals(j.getCompany()) && title.equals(j.getTitle()));
            }
            case "findByCompanyAndTitleAndDeletedFalse": {
                String company = (String) args[0];
                String title = (String) args[1];
                return store.values().stream().filter(j -> !j.isDeleted()
                        && company.equals(j.getCompany()) && title.equals(j.getTitle())).findFirst();
            }
            case "countByDeletedFalse":
                return (long) store.values().stream().filter(j -> !j.isDeleted()).count();
            case "findByDeletedFalse":
                return store.values().stream().filter(j -> !j.isDeleted()).toList();
            case "findByProvinceIsNullAndDeletedFalse":
            case "findByMonthsIsNullAndDeletedFalse":
            case "findByDailyMinIsNullAndDeletedFalse":
            case "findByStatusAndDeletedFalse":
                return java.util.List.of();
            case "countByStatusAndDeletedFalse":
                return 0L;
            case "findAll":
                return store.values().stream().toList();
            case "delete":
                store.remove(((Job) args[0]).getId());
                return null;
            case "toString":
                return "JobRepositoryStub";
            default:
                return null;
        }
    }

    private Job newJob(String company, String title) {
        Job job = new Job();
        job.setCompany(company);
        job.setTitle(title);
        return job;
    }

    @Test
    void 创建岗位写入哈希() {
        Job job = service.create(newJob("腾讯", "Java开发实习"));
        assertNotNull(job.getTitleHash());
        assertEquals(32, job.getTitleHash().length());
    }

    @Test
    void 创建重名岗位被拒() {
        service.create(newJob("腾讯", "Java开发实习"));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(newJob("腾讯", "Java开发实习")));
    }

    @Test
    void 并发唯一键冲突转业务异常而非500() {
        simulateUniqueConflict = true;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(newJob("腾讯", "Java开发实习")),
                "唯一键冲突应转成业务异常，不得漏出 DataIntegrityViolationException");
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void 编辑改名撞车被拒() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        service.create(newJob("腾讯", "岗位B"));
        Job patch = new Job();
        patch.setTitle("岗位B");
        assertThrows(IllegalArgumentException.class, () -> service.update(a.getId(), patch));
    }

    @Test
    void 编辑成自身同名通过() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        Job patch = new Job();
        patch.setTitle("岗位A");
        assertDoesNotThrow(() -> service.update(a.getId(), patch));
    }

    @Test
    void 删除为软删除且记录仍保留() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        Long id = a.getId();
        service.delete(id);
        assertTrue(store.get(id).isDeleted(), "应保留记录但标记删除");
        assertTrue(store.containsKey(id), "软删除不应物理移除记录");
    }

    @Test
    void 存量重复去重保留最小id并回填哈希() {
        // 绕过 create 的查重，直接构造两条重复（含大小写差异）与一条正常记录
        Job first = newJob("腾讯", "Java实习");
        first.setId(1L);
        Job dup = newJob("腾讯", "Java实习");
        dup.setId(2L);
        Job other = newJob("阿里", "后端实习");
        other.setId(3L);
        store.put(1L, first);
        store.put(2L, dup);
        store.put(3L, other);

        service.prepareUniqueIndex();

        assertTrue(store.containsKey(2L), "重复记录改为软删，不再物理删除");
        assertTrue(store.get(2L).isDeleted(), "重复记录应被标记软删");
        assertNull(store.get(2L).getTitleHash(), "软删记录应清空哈希释放唯一键");
        assertTrue(store.containsKey(1L), "应保留 id 最小者");
        assertFalse(store.get(1L).isDeleted(), "保留者应保持活跃");
        assertNotNull(store.get(1L).getTitleHash(), "保留记录应回填哈希");
        assertNotNull(store.get(3L).getTitleHash(), "正常记录也应回填哈希");
    }

    @Test
    void 大小写差异视为同一岗位() {
        Job first = newJob("Tencent", "Dev");
        first.setId(10L);
        Job dup = newJob("tencent", "dev");
        dup.setId(11L);
        store.put(10L, first);
        store.put(11L, dup);

        service.prepareUniqueIndex();

        assertTrue(store.containsKey(11L));
        assertTrue(store.get(11L).isDeleted(), "大小写不同也应判为重复并软删");
        assertFalse(store.get(10L).isDeleted());
    }

    @Test
    void 删除后清空哈希且可重建同名() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        String oldHash = a.getTitleHash();
        assertNotNull(oldHash);

        service.delete(a.getId());
        assertTrue(a.isDeleted());
        assertNull(a.getTitleHash(), "软删应清空哈希释放唯一键");

        // 重建同名岗位：不再被软删行挡住
        Job rebuilt = assertDoesNotThrow(() -> service.create(newJob("腾讯", "岗位A")));
        assertNotNull(rebuilt.getTitleHash());
        assertEquals(oldHash, rebuilt.getTitleHash(), "同名重建后哈希应与原值一致");
    }

    @Test
    void 软删岗位对业务入口不可见() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        service.delete(a.getId());
        assertThrows(IllegalArgumentException.class, () -> service.get(a.getId()),
                "软删岗位应视为不存在");
        assertThrows(IllegalArgumentException.class,
                () -> service.update(a.getId(), newJob("腾讯", "岗位B")),
                "软删岗位不可编辑");
    }

    @Test
    void 编辑软删岗位不会重设哈希() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        Long id = a.getId();
        assertNotNull(a.getTitleHash(), "创建后应有哈希");
        service.delete(id);
        assertNull(store.get(id).getTitleHash(), "删除后哈希应被清空");
        Job patch = new Job();
        patch.setTitle("改名尝试");
        assertThrows(IllegalArgumentException.class, () -> service.update(id, patch));
        assertNull(store.get(id).getTitleHash(), "编辑被拒后哈希仍应为空，不占用唯一键");
        assertEquals("岗位A", store.get(id).getTitle(), "编辑被拒后标题不应被修改");
    }

    @Test
    void 负薪资被拒绝() {
        Job job = newJob("腾讯", "Java实习");
        job.setSalaryMin(-100);
        job.setSalaryMax(-50);
        assertThrows(IllegalArgumentException.class, () -> service.create(job));
    }

    @Test
    void 薪资上限低于下限被拒绝() {
        Job job = newJob("腾讯", "Java实习");
        job.setSalaryMin(300);
        job.setSalaryMax(100);
        assertThrows(IllegalArgumentException.class, () -> service.create(job));
    }

    @Test
    void 合法薪资通过校验() {
        Job job = newJob("腾讯", "Java实习");
        job.setSalaryMin(200);
        job.setSalaryMax(400);
        Job saved = assertDoesNotThrow(() -> service.create(job));
        assertNotNull(saved.getTitleHash());
    }

    @Test
    void 导入含非法薪资条目被跳过() {
        Job good = newJob("字节", "Java实习");
        good.setSalaryMin(200);
        Job badSalary = newJob("腾讯", "后端实习");
        badSalary.setSalaryMin(-1);

        int added = service.importJobs(java.util.List.of(good, badSalary));
        assertEquals(1, added);
    }

    @Test
    void 导入跳过非法条目不中断整批() {
        Job good = newJob("字节", "Java实习");
        Job badMonths = newJob("腾讯", "后端实习");
        badMonths.setMonths(999);
        Job badDays = newJob("阿里", "前端实习");
        badDays.setDays(20);
        Job badStatus = newJob("美团", "测试实习");
        badStatus.setStatus("UNKNOWN");

        int added = service.importJobs(java.util.List.of(good, badMonths, badDays, badStatus));

        assertEquals(1, added, "只有合法条目入库");
        assertEquals(1, store.values().stream().filter(j -> !j.isDeleted()).count());
        assertTrue(store.values().stream().anyMatch(j -> "字节".equals(j.getCompany())));
    }

    @Test
    void 投递失败不应把SKIPPED打回NEW() {
        assertEquals("SKIPPED", service.nextStatusAfterFailure("SKIPPED"));
        assertEquals("CONTACTED", service.nextStatusAfterFailure("CONTACTED"));
        assertEquals("NEW", service.nextStatusAfterFailure("NEW"));
        assertEquals("NEW", service.nextStatusAfterFailure(null), "历史脏数据 null 归一为 NEW");
    }

    @Test
    void 更新状态拒绝非法枚举() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(a.getId(), "BOGUS"));
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(a.getId(), null));
        assertEquals("NEW", store.get(a.getId()).getStatus(), "非法状态不应写入");
    }

    @Test
    void 状态下拉三态均可设置() {
        Job a = service.create(newJob("腾讯", "岗位A"));
        assertDoesNotThrow(() -> service.updateStatus(a.getId(), "CONTACTED"));
        assertEquals("CONTACTED", store.get(a.getId()).getStatus());
        assertDoesNotThrow(() -> service.updateStatus(a.getId(), "SKIPPED"));
        assertEquals("SKIPPED", store.get(a.getId()).getStatus());
        assertDoesNotThrow(() -> service.updateStatus(a.getId(), "NEW"));
        assertEquals("NEW", store.get(a.getId()).getStatus());
    }

    @Test
    void 软删行id更小时不误删活跃同名行() {
        // 场景：id=1 是软删的同名行（hash 残留），id=5 是活跃同名行
        Job deleted = newJob("腾讯", "岗位A");
        deleted.setId(1L);
        deleted.setDeleted(true);
        deleted.setTitleHash("stale-hash");
        Job active = newJob("腾讯", "岗位A");
        active.setId(5L);
        store.put(1L, deleted);
        store.put(5L, active);

        service.prepareUniqueIndex();

        assertNull(store.get(1L).getTitleHash(), "存量软删行的残留哈希应被清空");
        assertTrue(store.containsKey(5L), "活跃同名行不应被误删");
        assertFalse(store.get(5L).isDeleted(), "活跃行应作为唯一存活者保持活跃");
        assertNotNull(store.get(5L).getTitleHash(), "活跃行应被回填哈希");
    }

    private static final String BOSS_LONG_URL =
            "https://www.zhipin.com/job_detail/90fb1549eb50e16e0nx63ti5FFJQ.html"
                    + "?securityId=TEST_ONLY_NOT_A_REAL_TOKEN_" + "x".repeat(120)
                    + "&ka=company_more_job_90fb1549eb50e16e0nx63ti5FFJQ"
                    + "&extra=" + "x".repeat(520);
    private static final String BOSS_SHORT_URL =
            "https://www.zhipin.com/job_detail/90fb1549eb50e16e0nx63ti5FFJQ.html";

    @Test
    void 新增时BOSS长链接去掉一次性参数() {
        assertTrue(BOSS_LONG_URL.length() > 512, "测试样本应确实超过原列长");
        Job job = newJob("测试公司", "测试岗位");
        job.setUrl(BOSS_LONG_URL);
        Job saved = service.create(job);
        assertEquals(BOSS_SHORT_URL, saved.getUrl());
        assertTrue(saved.getUrl().length() < 100);
    }

    @Test
    void 编辑补填BOSS长链接也归一化() {
        Job job = service.create(newJob("测试公司", "测试岗位"));
        Job patch = new Job();
        patch.setUrl(BOSS_LONG_URL);
        Job updated = service.update(job.getId(), patch);
        assertEquals(BOSS_SHORT_URL, updated.getUrl(), "补填网址不应再触发数据库超长错误");
    }

    @Test
    void 超长普通链接报业务错误而不是409() {
        Job job = newJob("测试公司", "测试岗位");
        job.setUrl("https://example.com/job/" + "x".repeat(3000));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(job));
        assertTrue(e.getMessage().contains("2048"));
    }

    @Test
    void 非法协议链接被拒() {
        Job job = newJob("测试公司", "测试岗位");
        job.setUrl("javascript:alert(1)");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(job));
        assertTrue(e.getMessage().contains("http/https"));
    }
}
