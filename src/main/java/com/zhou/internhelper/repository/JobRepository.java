package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByCompanyAndTitle(String company, String title);

    boolean existsByCompanyAndTitle(String company, String title);

    List<Job> findByStatus(String status);

    long countByStatus(String status);

    /** 省份为空的（需要回填） */
    List<Job> findByProvinceIsNull();

    /** 结构化字段为空的（需要回填） */
    List<Job> findByMonthsIsNull();

    /** 日薪区间为空的（需要回填） */
    List<Job> findByDailyMinIsNull();

    /** 排序分页（offset 模式，用于跳页 / 按字段排序） */
    @Query("""
            select j from Job j
            where (:status is null or j.status = :status)
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
            where (:status is null or j.status = :status)
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
            where (:status is null or j.status = :status)
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
    Page<Job> findByStatus(String status, Pageable pageable);

    @Query("""
            select distinct j.district from Job j
            where j.province = :province and j.city = :city and j.district is not null
            order by j.district
            """)
    List<String> findDistricts(@Param("province") String province, @Param("city") String city);
}
