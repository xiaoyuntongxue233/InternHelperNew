package com.zhou.internhelper.controller;

import com.zhou.internhelper.entity.CandidateProfile;
import com.zhou.internhelper.repository.CandidateProfileRepository;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个人资料接口。单行存储（id=1），GET 不存在时返回默认值，PUT 保存。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final CandidateProfileRepository repo;

    public ProfileController(CandidateProfileRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public CandidateProfile get() {
        return repo.findById(1L).orElseGet(() -> {
            CandidateProfile p = new CandidateProfile();
            p.setId(1L);
            return p;
        });
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody CandidateProfile patch) {
        CandidateProfile p = repo.findById(1L).orElseGet(() -> {
            CandidateProfile n = new CandidateProfile();
            n.setId(1L);
            return n;
        });

        if (patch.getName() != null) p.setName(patch.getName());
        if (patch.getSchoolFull() != null) p.setSchoolFull(patch.getSchoolFull());
        if (patch.getSchoolShort() != null) p.setSchoolShort(patch.getSchoolShort());
        if (patch.getMajor() != null) p.setMajor(patch.getMajor());
        if (patch.getGrade() != null) p.setGrade(patch.getGrade());
        if (patch.getDegree() != null) p.setDegree(patch.getDegree());
        if (patch.getSkills() != null) p.setSkills(patch.getSkills());
        if (patch.getEvidence() != null) p.setEvidence(patch.getEvidence());
        if (patch.getMinMonths() != null) p.setMinMonths(patch.getMinMonths());
        if (patch.getMaxMonths() != null) p.setMaxMonths(patch.getMaxMonths());
        if (patch.getDaysPerWeek() != null) p.setDaysPerWeek(patch.getDaysPerWeek());
        if (patch.getStartFrom() != null) p.setStartFrom(patch.getStartFrom());
        if (patch.getAiAgentOk() != null) p.setAiAgentOk(patch.getAiAgentOk());
        if (patch.getStyle() != null) p.setStyle(patch.getStyle());
        if (patch.getMinLen() != null) p.setMinLen(patch.getMinLen());
        if (patch.getMaxLen() != null) p.setMaxLen(patch.getMaxLen());

        // 两条硬约束：每周 ≤5 天、实习 ≤6 个月
        p.setMaxMonths(clamp(p.getMaxMonths(), 2, 6));
        p.setMinMonths(clamp(p.getMinMonths(), 1, p.getMaxMonths()));
        p.setDaysPerWeek(clamp(p.getDaysPerWeek(), 1, 5));

        repo.save(p);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("profile", p);
        return resp;
    }

    private static int clamp(Integer v, int lo, int hi) {
        int x = v == null ? lo : v;
        if (x < lo) x = lo;
        if (x > hi) x = hi;
        return x;
    }
}
