package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.AiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsage, Long> {

    @Query("select coalesce(sum(u.costCny), 0) from AiUsage u " +
            "where u.userKey = :userKey and u.mode = 'PLATFORM'")
    BigDecimal sumPlatformCost(@Param("userKey") String userKey);

    List<AiUsage> findTop20ByOrderByCreatedAtDesc();
}
