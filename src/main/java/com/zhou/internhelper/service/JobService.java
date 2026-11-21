package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.ApplicationRecord;
import com.zhou.internhelper.entity.Job;
import com.zhou.internhelper.repository.ApplicationRecordRepository;
import com.zhou.internhelper.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

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
            where j.deleted = false
              and (:status is null or j.status = :status)
              and (:province is null or j.province = :province)
              and (:city is null or j.city = :city)
              and (:district is null or j.district = :district)
              and (:category is null or j.category = :category)
              and (:q is null
                   or lower(j.company) like lower(concat('%', :q, '%')) escape '!'
                   or lower(j.title) like lower(concat('%', :q, '%')) escape '!')
            """;

    private final JobRepository jobRepository;
    private final RegionCatalog regionCatalog;
    private final MetaParser metaParser;
    private final EntityManager entityManager;
    private final ApplicationRecordRepository recordRepository;

    public JobService(JobRepository jobRepository, RegionCatalog regionCatalog,
                      MetaParser metaParser, EntityManager entityManager,
                      ApplicationRecordRepository recordRepository) {
        this.jobRepository = jobRepository;
        this.regionCatalog = regionCatalog;
        this.metaParser = metaParser;
        this.entityManager = entityManager;
        this.recordRepository = recordRepository;
    }

    /**
     * 查询岗位。
     * - 不传 page / sort：游标模式（深翻页恒定耗时）
     * - 传了 page 或 sort：offset 模式（支持跳页与排序）
     */
    public Map<String, Object> search(String status, String province, String city, String district,
                                      String category, String q, Long cursor, Integer page,
                                      String sort, String order, int size) {
        // 选项 C：数值（size/page）继续宽容回落；格式类参数（sort/order）严格 400——
        // 这两个字段正常由表头点击生成，出现非法值意味着调用方写错或有探测行为。
        if (sort != null && !SORTABLE.contains(SORT_ALIAS.getOrDefault(sort, sort))) {
            throw new IllegalArgumentException("不支持的排序字段：" + sort);
        }
        if (order != null && !"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new IllegalArgumentException("order 只能是 asc 或 desc");
        }
        // 游标是「上一页最后一条的 id」，必须为正；0/负数 meaningless，直接 400
        if (cursor != null && cursor < 1) {
            throw new IllegalArgumentException("cursor 需 ≥ 1");
        }
        int limit = Math.max(1, Math.min(size, 100));
        Map<String, Object> params = new HashMap<>();
        params.put("status", blankToNull(status));
        params.put("province", blankToNull(province));
        params.put("city", blankToNull(city));
        params.put("district", blankToNull(district));
        params.put("category", blankToNull(category));
        // LIKE 通配符转义：q 里的 %/_ 按字面匹配，! 作 escape 字符（避开 MySQL 反斜杠模式差异）。
        // 顺序不可颠倒：先转义 ! 本身，再转义 % 与 _。
        String qText = blankToNull(q);
        params.put("q", qText == null ? null
                : qText.replace("!", "!!").replace("%", "!%").replace("_", "!_"));

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

    /** 业务入口统一走这里：软删岗位对外视为不存在 */
    public Job get(Long id) {
        return jobRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("岗位不存在或已删除：" + id));
    }

    private static final Set<String> STATUSES = Set.of("NEW", "CONTACTED", "SKIPPED");
    private static final Set<String> CATEGORIES = Set.of("INTERN", "FULLTIME");
    private static final Set<String> SALARY_TYPES = Set.of("DAILY", "MONTHLY");
    /** 与 Job.url 列长度一致；BOSS 带 securityId 的链接约 580 字符 */
    private static final int MAX_URL_LENGTH = 2048;

    /** 校验必填与枚举字段；blankPatch=true 时只校验传入的非 null 字段（用于 update） */
    private void validateJob(Job job, boolean blankPatch) {
        if (!blankPatch || job.getCompany() != null) {
            if (job.getCompany() == null || job.getCompany().isBlank()) {
                throw new IllegalArgumentException("公司不能为空");
            }
        }
        if (!blankPatch || job.getTitle() != null) {
            if (job.getTitle() == null || job.getTitle().isBlank()) {
                throw new IllegalArgumentException("岗位不能为空");
            }
        }
        if (job.getStatus() != null && !STATUSES.contains(job.getStatus())) {
            throw new IllegalArgumentException("岗位状态只能是 NEW / CONTACTED / SKIPPED");
        }
        if (job.getCategory() != null && !CATEGORIES.contains(job.getCategory())) {
            throw new IllegalArgumentException("岗位类型只能是 INTERN / FULLTIME");
        }
        if (job.getSalaryType() != null && !SALARY_TYPES.contains(job.getSalaryType())) {
            throw new IllegalArgumentException("薪资类型只能是 DAILY / MONTHLY");
        }
        if (job.getMonths() != null && (job.getMonths() < 1 || job.getMonths() > 24)) {
            throw new IllegalArgumentException("实习月数需在 1–24 之间");
        }
        if (job.getDays() != null && (job.getDays() < 1 || job.getDays() > 7)) {
            throw new IllegalArgumentException("每周出勤需在 1–7 天之间");
        }
        // 薪资非负且上限不低于下限（create/update/import 三条路径共用此校验）
        if (job.getSalaryMin() != null && job.getSalaryMin() < 0) {
            throw new IllegalArgumentException("薪资不能为负数");
        }
        if (job.getSalaryMax() != null) {
            if (job.getSalaryMax() < 0) {
                throw new IllegalArgumentException("薪资不能为负数");
            }
            if (job.getSalaryMin() != null && job.getSalaryMax() < job.getSalaryMin()) {
                throw new IllegalArgumentException("薪资上限不能低于下限");
            }
        }
    }

    @Transactional
    public Job create(Job job) {
        validateJob(job, false);
        job.setUrl(normalizeUrl(job.getUrl()));
        String hash = titleHash(job.getCompany(), job.getTitle());
        if (jobRepository.findByTitleHashAndDeletedFalse(hash).isPresent()
                || jobRepository.existsByCompanyAndTitleAndDeletedFalse(job.getCompany(), job.getTitle())) {
            throw new IllegalArgumentException("该公司的这个岗位已存在：" + job.getCompany() + " / " + job.getTitle());
        }
        job.setTitleHash(hash);
        normalizeRegion(job);
        applySalary(job);
        applyMeta(job);
        classify(job);
        // saveAndFlush 让唯一约束在方法内触发：并发下两个请求可能同时通过上面的查重，
        // 后提交者会撞 uk_job_title_hash。当场捕获并转业务异常，避免延迟到事务提交后
        // 在 Controller 之外抛出 DataIntegrityViolationException 落到 500。
        try {
            return jobRepository.saveAndFlush(job);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("该公司的这个岗位已存在：" + job.getCompany() + " / " + job.getTitle());
        }
    }

    /** 修改岗位：只覆盖传入的字段，返回更新后的对象 */
    @Transactional
    public Job update(Long id, Job patch) {
        validateJob(patch, true);
        Job job = get(id);
        // 纵深防御：即便入口已过滤软删，这里再挡一次，避免将来改用 findById 时
        // 让软删岗位重设 titleHash、重新占用唯一键
        if (job.isDeleted()) {
            throw new IllegalArgumentException("已删除的岗位不能编辑");
        }
        if (patch.getCompany() != null) job.setCompany(patch.getCompany());
        if (patch.getTitle() != null) job.setTitle(patch.getTitle());
        // 改名后重新查重（排除自身）：堵住编辑绕过去重
        String hash = titleHash(job.getCompany(), job.getTitle());
        var existing = jobRepository.findByTitleHashAndDeletedFalse(hash);
        if (existing.isPresent() && !existing.get().getId().equals(id)) {
            throw new IllegalArgumentException("该公司的这个岗位已存在：" + job.getCompany() + " / " + job.getTitle());
        }
        job.setTitleHash(hash);
        if (patch.getRawLocation() != null) {
            job.setRawLocation(patch.getRawLocation());
            normalizeRegion(job);
        }
        if (patch.getUrl() != null) job.setUrl(normalizeUrl(patch.getUrl()));
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

    /** 删除岗位：软删除，保留投递记录与统计口径；清空 titleHash 释放唯一键，允许同名重建 */
    @Transactional
    public void delete(Long id) {
        Job job = get(id);
        job.setDeleted(true);
        job.setTitleHash(null);
        jobRepository.save(job);
    }

    /** 导入：按 公司+岗位 去重（含哈希），顺便拆地点、抽时长和日薪。
     *  与 create 不同：坏数据（非法枚举/越界月数天数）只跳过该条并记 warn，不中断整批。 */
    @Transactional
    public int importJobs(List<Job> jobs) {
        int added = 0;
        for (Job job : jobs) {
            try {
                validateJob(job, false);
            } catch (IllegalArgumentException e) {
                log.warn("跳过非法导入岗位 {} / {}：{}", job.getCompany(), job.getTitle(), e.getMessage());
                continue;
            }
            String hash = titleHash(job.getCompany(), job.getTitle());
            if (jobRepository.findByTitleHashAndDeletedFalse(hash).isPresent()
                    || jobRepository.existsByCompanyAndTitleAndDeletedFalse(job.getCompany(), job.getTitle())) {
                continue;
            }
            job.setTitleHash(hash);
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

    /**
     * 启动时一次性数据治理，全部在 Java 层完成，不依赖窗口函数或 md5() 等方言，兼容 MySQL 5.7。
     * 分三阶段且顺序不可颠倒（阶段间 flush，避免唯一键短暂冲突）：
     *   1) 清空所有软删行的 titleHash，释放唯一键（含历史遗留数据）；
     *   2) 只对活跃行按公司+岗位分组，保留 id 最小者，其余重复行改为软删+清 hash（保留投递记录引用）；
     *   3) 回填保留下来的活跃行 titleHash。
     */
    @Transactional
    public int prepareUniqueIndex() {
        // 阶段 1：释放软删行占用的唯一键
        List<Job> deletedRows = jobRepository.findByDeletedTrue();
        int released = 0;
        for (Job job : deletedRows) {
            if (job.getTitleHash() != null) {
                job.setTitleHash(null);
                jobRepository.save(job);
                released++;
            }
        }
        if (released > 0) {
            entityManager.flush();
            log.info("释放存量软删岗位的唯一哈希 {} 条", released);
        }

        // 阶段 2：活跃行去重。只处理未删除岗位，避免把软删行当成保留者而误删活跃行
        List<Job> active = jobRepository.findByDeletedFalse();
        Map<String, List<Job>> groups = new LinkedHashMap<>();
        for (Job job : active) {
            String key = normalizeKey(job.getCompany(), job.getTitle());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(job);
        }
        int softDeleted = 0;
        List<Job> survivors = new ArrayList<>();
        for (List<Job> group : groups.values()) {
            Job keep = group.stream().min(java.util.Comparator.comparing(Job::getId)).orElse(group.get(0));
            survivors.add(keep);
            for (Job job : group) {
                if (!job.getId().equals(keep.getId())) {
                    // 物理删除会留下孤儿投递记录，改为软删 + 清 hash
                    job.setDeleted(true);
                    job.setTitleHash(null);
                    jobRepository.save(job);
                    softDeleted++;
                }
            }
        }
        if (softDeleted > 0) {
            entityManager.flush();
            log.warn("软删归档存量重复岗位 {} 条（每组保留 id 最小者）", softDeleted);
        }

        // 阶段 3：回填保留下来的活跃行
        int filled = 0;
        for (Job job : survivors) {
            String hash = titleHash(job.getCompany(), job.getTitle());
            if (!hash.equals(job.getTitleHash())) {
                job.setTitleHash(hash);
                jobRepository.save(job);
                filled++;
            }
        }
        if (filled > 0) {
            log.info("回填岗位去重哈希 {} 条", filled);
        }
        return released + softDeleted + filled;
    }

    private String normalizeKey(String company, String title) {
        return (company == null ? "" : company.trim().toLowerCase())
                + "|" + (title == null ? "" : title.trim().toLowerCase());
    }

    /** 启动时回填历史数据 */
    @Transactional
    public int backfill() {
        List<Job> regionList = jobRepository.findByProvinceIsNullAndDeletedFalse();
        for (Job job : regionList) {
            normalizeRegion(job);
        }
        jobRepository.saveAll(regionList);

        List<Job> metaList = jobRepository.findByMonthsIsNullAndDeletedFalse();
        for (Job job : metaList) {
            applyMeta(job);
        }
        jobRepository.saveAll(metaList);

        List<Job> salaryList = jobRepository.findByDailyMinIsNullAndDeletedFalse();
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
        List<Job> all = jobRepository.findByDeletedFalse();
        for (Job job : all) {
            String before = job.getCategory();
            classify(job);
            if (before == null || !before.equals(job.getCategory())) {
                jobRepository.save(job);
            }
        }
        return regionList.size() + metaList.size() + salaryList.size();
    }

    /**
     * 网址归一化：BOSS 链接里的 securityId / ka 是一次性 tracking 参数，去掉后既短又不会过期。
     * 其他站点保留完整查询串；超长或非法协议直接报业务错误，避免落到数据库触发误导性的 409。
     */
    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("网址格式不正确");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("网址只支持 http/https");
        }
        String host = uri.getHost();
        String path = uri.getPath();
        boolean boss = host != null && path != null && path.startsWith("/job_detail/")
                && (host.equalsIgnoreCase("www.zhipin.com") || host.equalsIgnoreCase("zhipin.com"));
        if (boss) {
            return scheme.toLowerCase() + "://" + host.toLowerCase() + path;
        }
        if (value.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("网址不能超过 " + MAX_URL_LENGTH + " 个字符，请删除多余的 tracking 参数");
        }
        return value;
    }

    /** 公司+岗位 归一化哈希：trim + 小写后用 MD5，避免联合唯一索引超长 */
    private String titleHash(String company, String title) {
        String key = (company == null ? "" : company.trim().toLowerCase())
                + "|" + (title == null ? "" : title.trim().toLowerCase());
        try {
            byte[] bytes = MessageDigest.getInstance("MD5").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    @Transactional
    public Job updateStatus(Long id, String status) {
        // 状态下拉/批量改状态都会走这里，必须校验枚举，避免写入任意字符串
        if (status == null || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("岗位状态只能是 NEW / CONTACTED / SKIPPED");
        }
        Job job = get(id);
        job.setStatus(status);
        return jobRepository.save(job);
    }

    /**
     * 记录一次投递并更新状态（单条 / 批量共用）。
     * 幂等：同岗位当天复用一条记录；只在成功时置 CONTACTED，失败不把 SKIPPED/CONTACTED 打回 NEW。
     * message 由前端传入（已生成并展示的话术），此处绝不隐式调用 AI。
     */
    @Transactional
    public Map<String, Object> markContacted(Long id, String message, boolean success, String failReason) {
        Job job = get(id);
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        var today = recordRepository.findByJobIdAndSentAtBetweenOrderBySentAtDesc(job.getId(), start, end);
        ApplicationRecord record = today.isEmpty() ? new ApplicationRecord() : today.get(0);
        boolean created = record.getId() == null;

        record.setJobId(job.getId());
        record.setCompany(job.getCompany());
        record.setTitle(job.getTitle());
        // 已有记录且本次没带话术时保留原话术，避免幂等更新把内容清空
        if (message != null || created) {
            record.setMessage(message);
        }
        record.setSuccess(success);
        record.setFailReason(success ? null : failReason);
        if (created) {
            record.setSentAt(LocalDateTime.now());
        }
        record = recordRepository.save(record);

        String current = job.getStatus();
        if (success) {
            updateStatus(job.getId(), "CONTACTED");
        } else {
            String next = nextStatusAfterFailure(current);
            if (!java.util.Objects.equals(next, current)) {
                updateStatus(job.getId(), next);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.getId());
        result.put("success", success);
        result.put("message", record.getMessage());
        result.put("reused", !created);
        return result;
    }

    /**
     * 标记投递「失败」后的状态决策（纯逻辑，便于契约测试）。
     * 只有当前是 NEW 或历史脏数据 null 时才归一为 NEW；
     * SKIPPED / CONTACTED 一律保持不变，失败不得把已跳过/已联系的岗位打回 NEW。
     */
    public String nextStatusAfterFailure(String current) {
        if (current == null || "NEW".equals(current)) {
            return "NEW";
        }
        return current;
    }

    public long countByStatus(String status) {
        return jobRepository.countByStatusAndDeletedFalse(status);
    }

    public List<Job> listPending() {
        return jobRepository.findByStatusAndDeletedFalse("NEW");
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
