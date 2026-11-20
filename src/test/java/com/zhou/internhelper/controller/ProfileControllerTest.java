package com.zhou.internhelper.controller;

import com.zhou.internhelper.entity.CandidateProfile;
import com.zhou.internhelper.repository.CandidateProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 个人资料保存契约测试：锁定 minLen/maxLen 的 20–200 收敛行为（N-07 复核）。
 * 该行为源码已实现，此单测用于防止未来回归，并作为 N-07 报告质疑的确定性证据。
 * 不用 Mockito（Java 25 兼容问题），用 JDK 动态代理的内存版 Repository。
 */
class ProfileControllerTest {

    private ProfileController controller;
    private CandidateProfile stored;

    @BeforeEach
    void setUp() {
        CandidateProfileRepository repo = (CandidateProfileRepository) Proxy.newProxyInstance(
                CandidateProfileRepository.class.getClassLoader(),
                new Class<?>[]{CandidateProfileRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(stored);
                    case "save" -> {
                        stored = (CandidateProfile) args[0];
                        yield stored;
                    }
                    case "toString" -> "ProfileRepoStub";
                    default -> null;
                });
        controller = new ProfileController(repo);
    }

    private CandidateProfile patchWithLen(Integer minLen, Integer maxLen) {
        CandidateProfile p = new CandidateProfile();
        p.setMinLen(minLen);
        p.setMaxLen(maxLen);
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 字数越界被收敛到20到200() {
        Map<String, Object> resp = controller.save(patchWithLen(1, 9999));
        CandidateProfile saved = (CandidateProfile) resp.get("profile");
        assertEquals(20, saved.getMinLen(), "minLen=1 应收敛到 20");
        assertEquals(200, saved.getMaxLen(), "maxLen=9999 应收敛到 200");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 合法字数原样保留() {
        Map<String, Object> resp = controller.save(patchWithLen(50, 120));
        CandidateProfile saved = (CandidateProfile) resp.get("profile");
        assertEquals(50, saved.getMinLen());
        assertEquals(120, saved.getMaxLen());
    }

    @Test
    @SuppressWarnings("unchecked")
    void maxLen不低于收敛后的minLen() {
        // minLen=300 收敛到 200，maxLen=30 的下界取收敛后的 minLen=200
        Map<String, Object> resp = controller.save(patchWithLen(300, 30));
        CandidateProfile saved = (CandidateProfile) resp.get("profile");
        assertEquals(200, saved.getMinLen());
        assertEquals(200, saved.getMaxLen(), "maxLen 下界取收敛后的 minLen");
    }
}
