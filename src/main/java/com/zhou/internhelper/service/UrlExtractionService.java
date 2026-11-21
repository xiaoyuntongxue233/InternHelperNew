package com.zhou.internhelper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 网页端「贴网址自动取数」的异步任务。
 *
 * <p>服务端不抓网页：只启动本机已登录的 Edge 脚本去取正文，再复用 /api/jobs/extract 做抽取。
 * 安全验证页会等待用户在本机手动处理，脚本不绕验证码。</p>
 */
@Service
public class UrlExtractionService {

    private static final Logger log = LoggerFactory.getLogger(UrlExtractionService.class);
    private static final Pattern URL_ONLY = Pattern.compile("(?i)^https?://\\S+$");
    private static final int MAX_RUNNING = 2;
    private static final int PROCESS_TIMEOUT_SEC = 180;
    private static final long KEEP_HOURS = 2;

    private final ObjectMapper mapper = new ObjectMapper();
    private final int port;
    private final String configuredNode;
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_RUNNING, r -> {
        Thread t = new Thread(r, "url-extract");
        t.setDaemon(true);
        return t;
    });

    public UrlExtractionService(@Value("${server.port:12231}") int port,
                                @Value("${app.node-path:}") String configuredNode) {
        this.port = port;
        this.configuredNode = configuredNode;
    }

    public record SubmitResult(String jobId, String status) {
    }

    public record StatusResult(String status, Map<String, Object> draft, String message) {
    }

    /** 任务状态可变对象：写入在线程池，读取在 HTTP 线程，全部 volatile + synchronized */
    public static final class JobState {
        private volatile String status = "RUNNING";
        private volatile Map<String, Object> draft;
        private volatile String message = "";
        private final Instant updatedAt = Instant.now();

        public String status() { return status; }
        public Map<String, Object> draft() { return draft; }
        public String message() { return message; }
        public Instant updatedAt() { return updatedAt; }

        synchronized void succeed(Map<String, Object> draft) {
            this.draft = draft;
            this.status = "SUCCESS";
            this.message = "";
        }

        synchronized void fail(String message) {
            this.message = message == null ? "未知错误" : message;
            this.status = "FAILED";
        }
    }

    public SubmitResult submit(String url) {
        if (!StringUtils.hasText(url) || !URL_ONLY.matcher(url.trim()).matches()) {
            throw new IllegalArgumentException("请提供 http/https 开头的岗位链接");
        }
        prune();
        long running = jobs.values().stream().filter(s -> "RUNNING".equals(s.status())).count();
        if (running >= MAX_RUNNING) {
            throw new IllegalArgumentException("同时最多处理 " + MAX_RUNNING + " 个网址任务，请稍后再试");
        }
        String jobId = UUID.randomUUID().toString().replace("-", "");
        jobs.put(jobId, new JobState());
        executor.submit(() -> run(jobId, url.trim()));
        return new SubmitResult(jobId, "RUNNING");
    }

    public StatusResult status(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            throw new IllegalArgumentException("任务编号不能为空");
        }
        JobState state = jobs.get(jobId);
        if (state == null) {
            throw new IllegalArgumentException("任务不存在或已过期，请重新提交");
        }
        return new StatusResult(state.status(), state.draft(), state.message());
    }

    private void prune() {
        Instant cutoff = Instant.now().minus(KEEP_HOURS, ChronoUnit.HOURS);
        jobs.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
    }

    private void run(String jobId, String url) {
        JobState state = jobs.get(jobId);
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "intern-helper-url-extract");
        Path out = null;
        Path shot = null;
        Path logFile = null;
        try {
            Files.createDirectories(dir);
            out = dir.resolve(jobId + ".json");
            shot = dir.resolve(jobId + ".png");
            logFile = dir.resolve(jobId + ".log");
            Map<String, Object> summary = runScript(url, out, shot, logFile);
            Object raw = summary.get("draft");
            @SuppressWarnings("unchecked")
            Map<String, Object> draft = raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
            state.succeed(draft);
        } catch (Exception e) {
            log.warn("网址抽取任务 {} 失败：{}", jobId, e.getMessage());
            state.fail(readTail(logFile, e.getMessage()));
        } finally {
            deleteQuietly(out);
            deleteQuietly(shot);
            deleteQuietly(logFile);
        }
    }

    /**
     * 真正启动本机脚本。单独拆出来是为了单测能用子类替换，不依赖 Node/Edge 环境。
     */
    protected Map<String, Object> runScript(String url, Path out, Path shot, Path logFile) throws Exception {
        String script = Paths.get(System.getProperty("user.dir"), "..", "打招呼脚本", "extract_job.mjs")
                .toAbsolutePath().normalize().toString();
        if (!Files.exists(Paths.get(script))) {
            throw new IllegalStateException("找不到本地取数脚本 extract_job.mjs");
        }
        String node = resolveNode();
        String api = "http://127.0.0.1:" + port + "/api/jobs/extract";
        List<String> command = new ArrayList<>(List.of(
                node, script,
                "--url", url,
                "--start",
                "--api", api,
                "--out", out.toString(),
                "--screenshot", shot.toString(),
                "--wait", "150"
        ));

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        Process process = pb.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("取数超时（" + PROCESS_TIMEOUT_SEC + " 秒），请检查 Edge 窗口是否需要手动验证");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("取数脚本执行失败：" + readTail(logFile, "无日志"));
        }
        if (!Files.exists(out)) {
            throw new IllegalStateException("取数脚本未产出草稿文件：" + readTail(logFile, "无日志"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = mapper.readValue(out.toFile(), Map.class);
        return summary;
    }

    private String resolveNode() throws Exception {
        if (StringUtils.hasText(configuredNode) && Files.exists(Paths.get(configuredNode))) {
            return configuredNode;
        }
        List<String> candidates = List.of(
                "C:\\Users\\15266\\.workbuddy\\binaries\\node\\versions\\22.22.2-6\\node.exe",
                "node"
        );
        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 试下一个候选
            }
        }
        throw new IllegalStateException("找不到 Node.js，请在 application.yml 配置 app.node-path");
    }

    private static String readTail(Path log, String fallback) {
        if (log == null || !Files.exists(log)) {
            return fallback;
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 5);
            return String.join(" | ", lines.subList(from, lines.size()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }
}
