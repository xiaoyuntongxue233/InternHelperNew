package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class JobService {

    /** 允许排序的字段白名单，避免前端传乱七八糟的字段进来 */
    private static final Set<String> SORTABLE = Set.of("months", "days", "dailyMin", "dailyMax", "id", "company", "status");

    /** 旧的排序参数名兼容 → 新字段 */
    private static final Map<String, String> SORT_ALIAS = Map.of(
            "dailyPay", "dailyMin",
            "salary", "dailyMin"
    );

    /** 判定为社招的日薪上限（≈ 月薪 2 万） */
    private static final int FULLTIME_DAILY_THRESHOLD = 900;

    /** 标题里出现这些词，基本是社招 */
    private static final String[] FULLTIME_KEYWORDS = {
            "高级", "资深", "专家", "架构师", "技术专家", "p6", "p7", "p8", "P6", "P7", "P8",
            "3-5年", "5-10年", "3年以上", "5年以上"
    };

    private static final String BASE_WHERE = """
            where (:status is null or j.status = :status)
              and (:province is null or j.province = :province)
              and (:city is null or j.city = :city)
              and (:district is null or j.district = :district)
              and (:category is null or j.category = :category)
              and (:q is null
                   or lower(j.company) like lower(concat('%', :q, '%'))
                   or lower(j.title) like lower(concat('%', :q, '%')))
            """;

    private final JobRepository jobRepository;
    private final RegionCatalog regionCatalog;
    private final MetaParser metaParser;
    private final EntityManager entityManager;

    public JobService(JobRepository jobRepository, RegionCatalog regionCatalog,
                      MetaParser metaParser, EntityManager entityManager) {
        this.jobRepository = jobRepository;
        this.regionCatalog = regionCatalog;
        this.metaParser = metaParser;
        this.entityManager = entityManager;
    }

    /**
     * 查询岗位。
     * - 不传 page / sort：游标模式（深翻页恒定耗时）
     * - 传了 page 或 sort：offset 模式（支持跳页与排序）
     */
    public Map<String, Object> search(String status, String province, String city, String district,
                                      String category, String q, Long cursor, Integer page,
                                      String sort, String order, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        Map<String, Object> params = new HashMap<>();
        params.put("status", blankToNull(status));
        params.put("province", blankToNull(province));
        params.put("city", blankToNull(city));
        params.put("district", blankToNull(district));
        params.put("category", blankToNull(category));
        params.put("q", blankToNull(q));

        long total = count(params);
        boolean useOffset = page != null || sort != null;

        Map<String, Object> map = new LinkedHashMap<>();
        if (useOffset) {
            int pageNo = page == null ? 1 : Math.max(1, page);
            int maxPage = Math.max(1, (int) Math.ceil(total / (double) limit));
            pageNo = Math.min(pageNo, maxPage);

            List<Job> items = queryPage(params, sort, order, (pageNo - 1) * limit, limit);
            map.put("items", items);
            map.put("total", total);
            map.put("size", limit);
            map.put("page", pageNo);
            map.put("totalPages", maxPage);
            map.put("mode", "offset");
            map.put("hasMore", pageNo < maxPage);
            map.put("nextCursor", null);
            return map;
        }

        List<Job> rows = queryCursor(params, cursor, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Job> items = hasMore ? rows.subList(0, limit) : rows;
        map.put("items", items);
        map.put("total", total);
        map.put("size", limit);
        map.put("mode", "cursor");
        map.put("hasMore", hasMore);
        map.put("nextCursor", items.isEmpty() ? null : items.get(items.size() - 1).getId());
        return map;
    }

    private long count(Map<String, Object> params) {
        Query query = entityManager.createQuery("select count(j) from Job j " + BASE_WHERE);
        params.forEach(query::setParameter);
        return (Long) query.getSingleResult();
    }

    private List<Job> queryCursor(Map<String, Object> params, Long cursor, int limit) {
        String jpql = "select j from Job j " + BASE_WHERE
                + (cursor == null ? "" : " and j.id < :cursor")
                + " order by j.id desc";
        TypedQuery<Job> query = entityManager.createQuery(jpql, Job.class);
        params.forEach(query::setParameter);
        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }
        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * offset 分页 + 排序。
     * 排序字段走白名单，null 统一排最后（MySQL 不支持 NULLS LAST，用 CASE 实现）。
     */
    private List<Job> queryPage(Map<String, Object> params, String sort, String order, int offset, int limit) {
        String field = sort == null ? "id" : SORT_ALIAS.getOrDefault(sort, sort);
        if (!SORTABLE.contains(field)) {
            field = "id";
        }
        String dir = "asc".equalsIgnoreCase(order) ? "asc" : "desc";

        StringBuilder jpql = new StringBuilder("select j from Job j ").append(BASE_WHERE);
        if ("id".equals(field)) {
            jpql.append(" order by j.id ").append(dir);
        } else {
            // 字段名来自白名单，不存在注入风险
            jpql.append(" order by case when j.").append(field).append(" is null then 1 else 0 end, j.")
                    .append(field).append(' ').append(dir).append(", j.id desc");
        }

        TypedQuery<Job> query = entityManager.createQuery(jpql.toString(), Job.class);
        params.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    public Job get(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在：" + id));
    }

    @Transactional
    public Job create(Job job) {
        if (jobRepository.existsByCompanyAndTitle(job.getCompany(), job.getTitle())) {
            throw new IllegalArgumentException("该公司的这个岗位已存在：" + job.getCompany() + " / " + job.getTitle());
        }
        normalizeRegion(job);
        applySalary(job);
        applyMeta(job);
        classify(job);
        return jobRepository.save(job);
    }

    /** 修改岗位：只覆盖传入的字段，返回更新后的对象 */
    @Transactional
    public Job update(Long id, Job patch) {
        Job job = get(id);
        if (patch.getCompany() != null) job.setCompany(patch.getCompany());
        if (patch.getTitle() != null) job.setTitle(patch.getTitle());
        if (patch.getRawLocation() != null) {
            job.setRawLocation(patch.getRawLocation());
            normalizeRegion(job);
        }
        if (patch.getUrl() != null) job.setUrl(patch.getUrl());
        if (patch.getSkills() != null) job.setSkills(patch.getSkills());
        if (patch.getJdText() != null) job.setJdText(patch.getJdText());
        if (patch.getStatus() != null) job.setStatus(patch.getStatus());
        if (patch.getCategory() != null) job.setCategory(patch.getCategory());
        if (patch.getMonths() != null) job.setMonths(patch.getMonths());
        if (patch.getDays() != null) job.setDays(patch.getDays());

        // 薪资：结构化字段优先，其次保留原文本
        if (patch.getSalaryType() != null || patch.getSalaryMin() != null) {
            job.setSalaryType(patch.getSalaryType());
            job.setSalaryMin(patch.getSalaryMin());
            job.setSalaryMax(patch.getSalaryMax());
            applySalary(job);
        } else if (patch.getSalary() != null && !patch.getSalary().equals(job.getSalary())) {
            job.setSalaryType(null);
            job.setSalaryMin(null);
            job.setSalaryMax(null);
            job.setSalary(patch.getSalary());
            int[] range = metaParser.parseDailyRange(patch.getSalary());
            job.setDailyMin(range == null ? null : range[0]);
            job.setDailyMax(range == null ? null : range[1]);
        }

        // 时长/出勤没显式传时，按新文本重新解析
        if (patch.getMonths() == null && patch.getDays() == null) {
            MetaParser.Schedule s = metaParser.parseSchedule(
                    (job.getJdText() == null ? "" : job.getJdText()) + " " + (job.getTitle() == null ? "" : job.getTitle()));
            if (s.months() != null) job.setMonths(s.months());
            if (s.days() != null) job.setDays(s.days());
        }
        if (patch.getCategory() == null) {
            classify(job);
        }
        return jobRepository.save(job);
    }

    /** 删除岗位 */
    @Transactional
    public void delete(Long id) {
        Job job = get(id);
        jobRepository.delete(job);
    }

    /** 导入：按 公司+岗位 去重，顺便拆地点、抽时长和日薪 */
    @Transactional
    public int importJobs(List<Job> jobs) {
        int added = 0;
        for (Job job : jobs) {
            if (job.getCompany() == null || job.getTitle() == null) {
                continue;
            }
            if (jobRepository.existsByCompanyAndTitle(job.getCompany(), job.getTitle())) {
                continue;
            }
            normalizeRegion(job);
            applySalary(job);
            applyMeta(job);
            classify(job);
            jobRepository.save(job);
            added++;
        }
        return added;
    }

    /** 按薪资和标题判断是实习还是社招 */
    public void classify(Job job) {
        String title = job.getTitle() == null ? "" : job.getTitle();
        // 标题里写了「实习」就按实习处理，哪怕日薪很高
        if (title.contains("实习")) {
            job.setCategory("INTERN");
            return;
        }
        if (job.getDailyMax() != null && job.getDailyMax() > FULLTIME_DAILY_THRESHOLD) {
            job.setCategory("FULLTIME");
            return;
        }
        for (String kw : FULLTIME_KEYWORDS) {
            if (title.contains(kw)) {
                job.setCategory("FULLTIME");
                return;
            }
        }
        job.setCategory("INTERN");
    }

    /** 把原始地点拆成 省/市/区 */
    public void normalizeRegion(Job job) {
        String raw = job.getRawLocation();
        if (raw == null || raw.isBlank()) {
            raw = job.getCity();
        }
        if (raw == null || raw.isBlank()) {
            return;
        }
        RegionCatalog.Region r = regionCatalog.parse(raw);
        job.setRawLocation(raw);
        job.setProvince(r.province());
        job.setCity(r.city());
        job.setDistrict(r.district());
    }

    /** 抽实习时长、每周出勤（薪资由 applySalary 处理） */
    public void applyMeta(Job job) {
        String text = String.join(" ",
                job.getJdText() == null ? "" : job.getJdText(),
                job.getTitle() == null ? "" : job.getTitle(),
                job.getSkills() == null ? "" : job.getSkills());
        MetaParser.Schedule schedule = metaParser.parseSchedule(text);
        if (schedule.months() != null) {
            job.setMonths(schedule.months());
        }
        if (schedule.days() != null) {
            job.setDays(schedule.days());
        }
    }

    /** 结构化薪资 → 展示文本 + 折算日薪区间 */
    private void applySalary(Job job) {
        String type = job.getSalaryType();
        Integer min = job.getSalaryMin();
        Integer max = job.getSalaryMax();
        if (type == null || min == null) {
            // 没填结构化薪资，退回解析文本
            int[] range = metaParser.parseDailyRange(job.getSalary());
            job.setDailyMin(range == null ? null : range[0]);
            job.setDailyMax(range == null ? null : range[1]);
            return;
        }
        int low = min;
        int high = max == null ? min : Math.max(min, max);

        if ("MONTHLY".equalsIgnoreCase(type)) {
            job.setSalaryType("MONTHLY");
            job.setSalary(formatMonthly(low, high));
            job.setDailyMin((int) Math.round(low / 21.75));
            job.setDailyMax((int) Math.round(high / 21.75));
        } else {
            job.setSalaryType("DAILY");
            job.setSalary(low == high ? low + "元/天" : low + "-" + high + "元/天");
            job.setDailyMin(low);
            job.setDailyMax(high);
        }
    }

    private String formatMonthly(int low, int high) {
        boolean roundK = low % 1000 == 0 && high % 1000 == 0 && low >= 1000 && high >= 1000;
        if (roundK) {
            return (low / 1000) + (low == high ? "" : "-" + (high / 1000)) + "K/月";
        }
        return low == high ? low + "元/月" : low + "-" + high + "元/月";
    }

    /** 启动时回填历史数据 */
    @Transactional
    public int backfill() {
        List<Job> regionList = jobRepository.findByProvinceIsNull();
        for (Job job : regionList) {
            normalizeRegion(job);
        }
        jobRepository.saveAll(regionList);

        List<Job> metaList = jobRepository.findByMonthsIsNull();
        for (Job job : metaList) {
            applyMeta(job);
        }
        jobRepository.saveAll(metaList);

        List<Job> salaryList = jobRepository.findByDailyMinIsNull();
        for (Job job : salaryList) {
            if (job.getSalaryType() != null && job.getSalaryMin() != null) {
                applySalary(job);
            } else {
                int[] range = metaParser.parseDailyRange(job.getSalary());
                if (range != null) {
                    job.setDailyMin(range[0]);
                    job.setDailyMax(range[1]);
                }
            }
        }
        jobRepository.saveAll(salaryList);

        // 岗位类型（实习 / 社招）重新判定
        List<Job> all = jobRepository.findAll();
        for (Job job : all) {
            String before = job.getCategory();
            classify(job);
            if (before == null || !before.equals(job.getCategory())) {
                jobRepository.save(job);
            }
        }
        return regionList.size() + metaList.size() + salaryList.size();
    }

    @Transactional
    public Job updateStatus(Long id, String status) {
        Job job = get(id);
        job.setStatus(status);
        return jobRepository.save(job);
    }

    public long countByStatus(String status) {
        return jobRepository.countByStatus(status);
    }

    public List<Job> listPending() {
        return jobRepository.findByStatus("NEW");
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
