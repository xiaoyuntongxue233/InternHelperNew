package com.zhou.internhelper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * 敏感配置加解密（当前用于入库的 BYO API Key）。
 *
 * <p>密钥与密文分离：优先用环境变量 INTERNHELPER_SECRET_KEY；否则用用户主目录下的
 * ~/.internhelper/secret.key（首次启动随机生成）；两者都不可用时退回机器特征派生。
 * 密钥文件不放在项目目录、不进 git。密文格式 enc:v1:&lt;base64(iv+cipher)&gt;，
 * 便于识别与将来升级算法；没有前缀的历史明文原样返回，下次保存时自动转为密文。</p>
 *
 * <p>安全边界：此方案防止「数据库文件泄露导致 Key 明文外泄」，
 * 但无法防止攻击者同时拿到数据库和同一台机器上的密钥文件。需要更强隔离时应
 * 配置外部密钥管理或只使用环境变量注入。</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String ENV_KEY = "INTERNHELPER_SECRET_KEY";
    private static final String KEY_FILE = ".internhelper/secret.key";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Spring 使用：按环境变量 → 密钥文件 → 机器特征的顺序解析密钥 */
    public SecretCipher() {
        this(resolveKey());
    }

    /** 单测使用：直接注入固定密钥，避免写用户主目录 */
    public SecretCipher(byte[] keyBytes) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            this.key = new SecretKeySpec(sha, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    public String encrypt(String plain) {
        if (!StringUtils.hasText(plain)) {
            return plain == null ? "" : plain;
        }
        if (plain.startsWith(PREFIX)) {
            return plain; // 已经是密文，避免重复加密
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("加密敏感配置失败", e);
        }
    }

    /**
     * 解密。无前缀的历史明文原样返回；解密失败返回 null 并告警，
     * 不让应用启动失败（例如换了机器、密钥文件丢失）。
     */
    public String decrypt(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // 存量明文，兼容读取
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                log.warn("加密配置格式异常，已忽略");
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("解密敏感配置失败（密钥不匹配或数据被篡改），已忽略该配置");
            return null;
        }
    }

    private static byte[] resolveKey() {
        String env = System.getenv(ENV_KEY);
        if (StringUtils.hasText(env)) {
            log.info("加密密钥来自环境变量 {}", ENV_KEY);
            return env.getBytes(StandardCharsets.UTF_8);
        }
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".internhelper");
            Path file = dir.resolve("secret.key");
            if (Files.exists(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (StringUtils.hasText(text)) {
                    log.info("加密来自本地密钥文件 {}", file);
                    return Base64.getDecoder().decode(text);
                }
            }
            Files.createDirectories(dir);
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            Files.writeString(file, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows 文件系统不支持 POSIX 权限，忽略
            }
            log.info("已生成加密密钥文件 {}", file);
            return generated;
        } catch (Exception e) {
            log.warn("无法使用本地密钥文件，退回机器特征派生密钥（安全性较低）：{}", e.getMessage());
            String machine = System.getProperty("user.name", "unknown")
                    + "|" + hostName() + "|internhelper-secret-v1";
            return machine.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
