package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /** 业务入口统一用此查询：软删岗位对外视为不存在 */
    Optional<Job> findByIdAndDeletedFalse(Long id);

    /** 查重只看未删除的岗位：软删不占用公司+岗位名称 */
    Optional<Job> findByCompanyAndTitleAndDeletedFalse(String company, String title);

    boolean existsByCompanyAndTitleAndDeletedFalse(String company, String title);

    /** 查重必须忽略软删行：软删行的 title_hash 会被清空，不占用唯一键 */
    Optional<Job> findByTitleHashAndDeletedFalse(String titleHash);

    /** 启动时清理存量软删行的 title_hash，释放唯一键 */
    List<Job> findByDeletedTrue();

    List<Job> findByStatusAndDeletedFalse(String status);

    long countByStatusAndDeletedFalse(String status);

    long countByDeletedFalse();

    /** 省份为空的（需要回填） */
    List<Job> findByProvinceIsNullAndDeletedFalse();

    /** 结构化字段为空的（需要回填） */
    List<Job> findByMonthsIsNullAndDeletedFalse();

    /** 日薪区间为空的（需要回填） */
    List<Job> findByDailyMinIsNullAndDeletedFalse();

    /** 全部未删除（回填用） */
    List<Job> findByDeletedFalse();

    /** 存量去重：按公司+岗位分组，找出重复者（保留逻辑在 Service） */
    @Query("select j.company, j.title, count(j) from Job j group by j.company, j.title having count(j) > 1")
    List<Object[]> findDuplicateGroups();

    @Query("select j from Job j where j.company = :company and j.title = :title and j.deleted = false order by j.id asc")
    List<Job> findAllActiveByCompanyAndTitle(@Param("company") String company, @Param("title") String title);

    /** 排序分页（offset 模式，用于跳页 / 按字段排序） */
    @Query("""
            select j from Job j
            where j.deleted = false
              and (:status is null or j.status = :status)
              and (:province is null or j.province = :province)
              and (:city is null or j.city = :city)
              and (:district is null or j.district = :district)
              and (:q is null
                   or lower(j.company) like lower(concat('%', :q, '%'))
                   or lower(j.title) like lower(concat('%', :q, '%')))
            """)
    Page<Job> searchPage(@Param("status") String status,
                         @Param("province") String province,
                         @Param("city") String city,
                         @Param("district") String district,
                         @Param("q") String q,
                         Pageable pageable);

    @Query("""
            select j from Job j
            where j.deleted = false
              and (:status is null or j.status = :status)
              and (:province is null or j.province = :province)
              and (:city is null or j.city = :city)
              and (:district is null or j.district = :district)
              and (:q is null
                   or lower(j.company) like lower(concat('%', :q, '%'))
                   or lower(j.title) like lower(concat('%', :q, '%')))
              and (:cursor is null or j.id < :cursor)
            order by j.id desc
            """)
    List<Job> searchByCursor(@Param("status") String status,
                             @Param("province") String province,
                             @Param("city") String city,
                             @Param("district") String district,
                             @Param("q") String q,
                             @Param("cursor") Long cursor,
                             Pageable pageable);

    @Query("""
            select count(j) from Job j
            where j.deleted = false
              and (:status is null or j.status = :status)
              and (:province is null or j.province = :province)
              and (:city is null or j.city = :city)
              and (:district is null or j.district = :district)
              and (:q is null
                   or lower(j.company) like lower(concat('%', :q, '%'))
                   or lower(j.title) like lower(concat('%', :q, '%')))
            """)
    long countByFilter(@Param("status") String status,
                       @Param("province") String province,
                       @Param("city") String city,
                       @Param("district") String district,
                       @Param("q") String q);

    /** 后端仍保留一个分页接口（后台/调试用） */
    Page<Job> findByStatusAndDeletedFalse(String status, Pageable pageable);

    @Query("""
            select distinct j.district from Job j
            where j.deleted = false and j.province = :province and j.city = :city and j.district is not null
            order by j.district
            """)
    List<String> findDistricts(@Param("province") String province, @Param("city") String city);
}
