package com.zhou.internhelper.service;

import com.zhou.internhelper.entity.GreetingVersion;
import com.zhou.internhelper.repository.GreetingVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 话术草稿留痕：只做追加与裁剪，不参与生成逻辑，避免影响既有的额度/事实校验流程。
 */
@Service
public class GreetingVersionService {

    /** 单岗位最多保留的版本数，超出后删最旧的 */
    public static final int MAX_PER_JOB = 20;

    private final GreetingVersionRepository repository;

    public GreetingVersionService(GreetingVersionRepository repository) {
        this.repository = repository;
    }

    /** 追加一条版本记录，并把该岗位的历史裁剪到 20 条以内 */
    @Transactional
    public GreetingVersion save(GreetingVersion version) {
        GreetingVersion saved = repository.save(version);
        List<GreetingVersion> all = repository.findByJobIdOrderByCreatedAtAsc(version.getJobId());
        if (all.size() > MAX_PER_JOB) {
            // 留新删旧；同时间戳时按 id 保证顺序稳定
            int excess = all.size() - MAX_PER_JOB;
            for (int i = 0; i < excess; i++) {
                repository.delete(all.get(i));
            }
        }
        return saved;
    }

    public List<GreetingVersion> list(Long jobId) {
        return repository.findByJobIdOrderByCreatedAtDesc(jobId);
    }
}
