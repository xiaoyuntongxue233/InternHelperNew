package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.ApplicationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, Long> {

    long countBySuccessTrue();

    long countBySuccessTrueAndSentAtBetween(LocalDateTime from, LocalDateTime to);

    boolean existsByCompany(String company);

    List<ApplicationRecord> findTop50ByOrderBySentAtDesc();

    /** 带 limit 的最近记录查询（limit 通过 Pageable 的 pageSize 控制） */
    List<ApplicationRecord> findByOrderBySentAtDesc(Pageable pageable);

    @Query("select r.company, count(r) from ApplicationRecord r where r.success = true group by r.company order by count(r) desc")
    List<Object[]> countGroupByCompany();

    @Query("select r.jobId, count(r) from ApplicationRecord r where r.success = true group by r.jobId")
    List<Object[]> countGroupByJob();

    long countBySentAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("select count(r) from ApplicationRecord r where r.success = false and r.sentAt between :from and :to")
    long countFailed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 幂等：查某岗位当天的最新一条投递记录 */
    List<ApplicationRecord> findByJobIdAndSentAtBetweenOrderBySentAtDesc(
            Long jobId, LocalDateTime from, LocalDateTime to);
}
