package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.ApplicationRecord;
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

    @Query("select r.company, count(r) from ApplicationRecord r where r.success = true group by r.company order by count(r) desc")
    List<Object[]> countGroupByCompany();

    @Query("select r.jobId, count(r) from ApplicationRecord r where r.success = true group by r.jobId")
    List<Object[]> countGroupByJob();

    long countBySentAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("select count(r) from ApplicationRecord r where r.success = false and r.sentAt between :from and :to")
    long countFailed(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
