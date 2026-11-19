package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findTop20ByOrderByCreatedAtDesc();

    Optional<ImportBatch> findFirstByOrderByCreatedAtDesc();
}
