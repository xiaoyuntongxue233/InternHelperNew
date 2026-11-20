package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.GreetingVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GreetingVersionRepository extends JpaRepository<GreetingVersion, Long> {

    List<GreetingVersion> findByJobIdOrderByCreatedAtDesc(Long jobId);

    /** 单岗位最多保留 20 条，超出时按时间从旧到新列出以便裁剪 */
    List<GreetingVersion> findByJobIdOrderByCreatedAtAsc(Long jobId);

    void deleteByJobId(Long jobId);
}
