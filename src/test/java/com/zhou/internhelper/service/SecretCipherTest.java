package com.zhou.internhelper.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** API Key 加解密契约测试 */
class SecretCipherTest {

    private final SecretCipher cipher = new SecretCipher("unit-test-secret".getBytes(StandardCharsets.UTF_8));

    @Test
    void 密文往返一致() {
        String plain = "sk-abcdefghijklmnopqrstuvwxyz";
        String encrypted = cipher.encrypt(plain);
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void 密文带版本前缀且不含明文() {
        String plain = "sk-supersecretkey1234";
        String encrypted = cipher.encrypt(plain);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertFalse(encrypted.contains(plain), "密文不应包含明文片段");
    }

    @Test
    void 相同明文两次加密结果不同() {
        String plain = "sk-samekey";
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain), "IV 随机，密文应不同");
    }

    @Test
    void 存量明文可原样读取() {
        String legacy = "sk-legacy-plaintext";
        assertEquals(legacy, cipher.decrypt(legacy), "旧明文应兼容，不清空用户配置");
    }

    @Test
    void 已加密内容不会被重复加密() {
        String encrypted = cipher.encrypt("sk-once");
        assertEquals(encrypted, cipher.encrypt(encrypted));
    }

    @Test
    void 篡改密文返回null而非抛异常() {
        String encrypted = cipher.encrypt("sk-tamper-me");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";
        assertNull(cipher.decrypt(tampered), "篡改应被 GCM 校验识别，返回 null 而不是崩溃");
    }

    @Test
    void 不同密钥无法解密() {
        String encrypted = cipher.encrypt("sk-cross-key");
        SecretCipher other = new SecretCipher("another-secret".getBytes(StandardCharsets.UTF_8));
        assertNull(other.decrypt(encrypted));
    }

    @Test
    void 空值处理() {
        assertEquals("", cipher.encrypt(""));
        assertEquals("", cipher.encrypt(null));
        assertNull(cipher.decrypt(""));
        assertNull(cipher.decrypt(null));
    }
}
