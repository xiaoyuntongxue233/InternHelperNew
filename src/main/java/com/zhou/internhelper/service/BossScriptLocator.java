package com.zhou.internhelper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 定位 tools/boss-greeting（打招呼脚本）目录。
 * DeliverService（真发）和 ContactSyncService（同步已投状态）共用。
 */
@Component
public class BossScriptLocator {

    @Value("${app.deliver.script-dir:../../打招呼脚本}")
    private String scriptDirConfig;

    private volatile Path cached;

    /** @return 打招呼脚本目录（含 send_boss.mjs） */
    public Path resolve() {
        Path hit = cached;
        if (hit != null && Files.isDirectory(hit) && Files.exists(hit.resolve("send_boss.mjs"))) {
            return hit;
        }

        List<Path> candidates = new ArrayList<>();
        Path cfg = Paths.get(scriptDirConfig);
        if (cfg.isAbsolute()) {
            candidates.add(cfg);
        } else {
            Path cwd = Paths.get("").toAbsolutePath();
            candidates.add(cwd.resolve(scriptDirConfig).normalize());
            candidates.add(cwd.resolve("..").resolve(scriptDirConfig).normalize());
            candidates.add(cwd.resolve("..").resolve("..").resolve(scriptDirConfig).normalize());
        }

        for (Path p : candidates) {
            if (Files.isDirectory(p) && Files.exists(p.resolve("send_boss.mjs"))) {
                cached = p.toAbsolutePath().normalize();
                return cached;
            }
        }
        throw new IllegalStateException("找不到打招呼脚本目录。已尝试：" + candidates
                + "\n请用 app.deliver.script-dir（或环境变量 DELIVER_SCRIPT_DIR）指定绝对路径");
    }

    /** 脚本的发送日志：out/send_log.jsonl */
    public Path sendLogPath() {
        return resolve().resolve("out").resolve("send_log.jsonl");
    }

    /** 与脚本一致的公司名归一化（去省略号与所有空白） */
    public static String normCompany(String s) {
        if (s == null) return "";
        return s.replace("...", "").replace("…", "")
                .replaceAll("[\\s\\u00a0\\u200b-\\u200d\\ufeff\\u3000]", "").trim();
    }
}
