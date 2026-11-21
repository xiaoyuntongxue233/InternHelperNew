package com.zhou.internhelper.service;

import com.zhou.internhelper.config.AiProperties;
import com.zhou.internhelper.config.BillingProperties;
import com.zhou.internhelper.config.GreetingProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThemeSettingsTest {

    private SettingsService service() {
        return new SettingsService(null, new AiProperties(), new BillingProperties(),
                new GreetingProperties(), new SecretCipher("test-key".getBytes()));
    }

    @Test
    void 接受四套主题与系统跟随设置() {
        SettingsService s = service();
        assertDoesNotThrow(() -> s.validatePatch(Map.of("theme", "grass")));
        assertDoesNotThrow(() -> s.validatePatch(Map.of("theme", "blue")));
        assertDoesNotThrow(() -> s.validatePatch(Map.of("theme", "dark")));
        assertDoesNotThrow(() -> s.validatePatch(Map.of("theme", "coffee")));
        assertDoesNotThrow(() -> s.validatePatch(Map.of("followSystemTheme", true)));
        assertDoesNotThrow(() -> s.validatePatch(Map.of("followSystemTheme", "false")));
    }

    @Test
    void 拒绝未知主题与非布尔系统跟随() {
        SettingsService s = service();
        assertThrows(IllegalArgumentException.class,
                () -> s.validatePatch(Map.of("theme", "purple")));
        assertThrows(IllegalArgumentException.class,
                () -> s.validatePatch(Map.of("followSystemTheme", "yes")));
    }

    @Test
    void 旧构造快照默认草绿且不跟随系统() {
        SettingsService.SettingsSnapshot snap = new SettingsService.SettingsSnapshot(
                "RULE", "concise", true, "", "", "PLATFORM", "", "",
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                8000, 300, java.math.BigDecimal.ZERO);
        Map<String, Object> publicMap = snap.toPublicMap("");
        assertEquals("grass", publicMap.get("theme"));
        assertEquals(false, publicMap.get("followSystemTheme"));
    }
}
