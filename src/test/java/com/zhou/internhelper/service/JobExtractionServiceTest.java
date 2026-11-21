package com.zhou.internhelper.service;

import com.zhou.internhelper.config.AiProperties;
import com.zhou.internhelper.config.BillingProperties;
import com.zhou.internhelper.config.GreetingProperties;
import com.zhou.internhelper.entity.AiUsage;
import com.zhou.internhelper.repository.AiUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 岗位信息抽取的安全契约测试：白名单、事实溯源、数值收敛、技能白名单、额度不超支。
 * 与项目其他测试一致，不用 Mockito（Java 25 兼容问题），用 JDK 动态代理做桩。
 */
class JobExtractionServiceTest {

    // 单价 1 元/1M：一次抽取预估约 0.0004 元，远低于 0.1 元测试预算
    private static final BigDecimal PRICE = BigDecimal.ONE;

    private static final String PAGE =
            "某科技有限公司 Java后端开发实习生 广州海珠区 6-8K 12薪\n"
                    + "任职要求：每周5天，实习3个月，熟悉 Java、Spring Boot、MySQL，了解 Redis。";

    private final AtomicReference<AiGreetingClient.AiResult> aiReply = new AtomicReference<>();
    private final AtomicReference<BigDecimal> usedCost = new AtomicReference<>(BigDecimal.ZERO);
    private final List<AiUsage> usages = new ArrayList<>();
    private final AtomicInteger saved = new AtomicInteger();
    private AiQuotaService quota;
    private JobExtractionService service;

    private static AiGreetingClient.AiResult reply(String json) {
        return new AiGreetingClient.AiResult(json, 100, 50, 0, true);
    }

    private SettingsService.SettingsSnapshot snapshot(boolean byo) {
        return new SettingsService.SettingsSnapshot(
                "AI", "concise", true,
                "https://api.deepseek.com", "deepseek-chat", byo ? "BYO" : "PLATFORM",
                byo ? "sk-user" : "", byo ? "" : "sk-platform",
                PRICE, PRICE, PRICE, PRICE,
                8000, 300, new BigDecimal("0.1"));
    }

    private SettingsService settingsWith(SettingsService.SettingsSnapshot snap) {
        SettingsService s = new SettingsService(null, new AiProperties(),
                new BillingProperties(), new GreetingProperties(), new SecretCipher("test-key".getBytes()));
        try {
            var f = SettingsService.class.getDeclaredField("snapshot");
            f.setAccessible(true);
            f.set(s, snap);
        } catch (Exception e) {
            throw new IllegalStateException("注入快照失败", e);
        }
        return s;
    }

    private AiUsageRepository repositoryStub() {
        return (AiUsageRepository) Proxy.newProxyInstance(
                AiUsageRepository.class.getClassLoader(),
                new Class<?>[]{AiUsageRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "sumPlatformCost" -> usedCost.get();
                    case "save" -> {
                        AiUsage u = (AiUsage) a[0];
                        usages.add(u);
                        saved.incrementAndGet();
                        yield u;
                    }
                    case "toString" -> "UsageRepoStub";
                    default -> null;
                });
    }

    private ExtractionAiClient aiStub() {
        return (ExtractionAiClient) Proxy.newProxyInstance(
                ExtractionAiClient.class.getClassLoader(),
                new Class<?>[]{ExtractionAiClient.class},
                (p, m, a) -> switch (m.getName()) {
                    case "buildExtractUserPrompt" -> "PROMPT:" + a[0];
                    case "extract" -> {
                        AiGreetingClient.AiResult result = aiReply.get();
                        if (result == null) {
                            throw new IllegalStateException("模型接口地址、模型名或 API Key 未配置");
                        }
                        yield result;
                    }
                    case "toString" -> "AiStub";
                    default -> null;
                });
    }

    @BeforeEach
    void setUp() {
        aiReply.set(reply("{}"));
        usedCost.set(BigDecimal.ZERO);
        usages.clear();
        saved.set(0);
        quota = new AiQuotaService(settingsWith(snapshot(false)), repositoryStub());
        service = new JobExtractionService(aiStub(), quota, new MetaParser());
    }

    private static String baseJson() {
        return "{\"company\":\"某科技有限公司\",\"title\":\"Java后端开发实习生\","
                + "\"rawLocation\":\"广州海珠区\",\"salaryText\":\"6-8K\","
                + "\"monthsText\":\"3个月\",\"daysText\":\"每周5天\","
                + "\"jdText\":\"每周5天，实习3个月，熟悉 Java\","
                + "\"skills\":\"Java, Spring Boot, MySQL, Redis\"}";
    }

    @Test
    void 正常抽取八字段全部回填并记账() {
        aiReply.set(reply(baseJson()));
        JobExtractionService.Result r = service.extract(PAGE);
        JobExtractionService.Draft d = r.draft();

        assertTrue(r.warnings().isEmpty(), "页面里都有依据时不应告警：" + r.warnings());
        assertEquals("某科技有限公司", d.company());
        assertEquals("Java后端开发实习生", d.title());
        assertEquals("广州海珠区", d.rawLocation());
        assertEquals("6-8K", d.salaryText());
        assertEquals("MONTHLY", d.salaryType());
        assertEquals(6000, d.salaryMin());
        assertEquals(8000, d.salaryMax());
        assertEquals(276, d.dailyMin());
        assertEquals(368, d.dailyMax());
        assertEquals(3, d.months());
        assertEquals(5, d.days());
        assertEquals("Java,Spring Boot,MySQL,Redis", d.skills());
        assertEquals("每周5天，实习3个月，熟悉 Java", d.jdText());
        assertEquals(1, saved.get(), "一次抽取只应记一笔 AI 用量");
        assertEquals("extract", usages.get(0).getTemplateId());
    }

    @Test
    void 字段白名单丢弃未知字段并告警() {
        aiReply.set(reply(baseJson().replace("{\"company\"",
                "{\"id\":999,\"status\":\"CONTACTED\",\"company\"")));
        JobExtractionService.Result r = service.extract(PAGE);

        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("已忽略模型返回的未知字段：id")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("已忽略模型返回的未知字段：status")));
        assertEquals("某科技有限公司", r.draft().company(), "合法字段仍应回填");
    }

    @Test
    void 事实溯源清空编造的公司和技能() {
        aiReply.set(reply(baseJson()
                .replace("某科技有限公司", "完全不存在的公司")
                .replace("Java, Spring Boot, MySQL, Redis", "Java, Python")));
        JobExtractionService.Result r = service.extract(PAGE);

        assertEquals("", r.draft().company());
        assertEquals("Java", r.draft().skills());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("公司名未在页面文本中找到")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("技能「Python」未在页面文本中找到")));
    }

    @Test
    void 数值字段按硬边界收敛() {
        String page = PAGE + " 18个月 9天/周";
        aiReply.set(reply(baseJson()
                .replace("\"monthsText\":\"3个月\"", "\"monthsText\":\"18个月\"")
                .replace("\"daysText\":\"每周5天\"", "\"daysText\":\"9天/周\"")));
        JobExtractionService.Result r = service.extract(page);

        assertEquals(12, r.draft().months());
        assertEquals(7, r.draft().days());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("2-12 范围")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("1-7 范围")));
    }

    @Test
    void 每日工时不被误识别为出勤天数() {
        String page = PAGE + " 每天工作8小时";
        aiReply.set(reply(baseJson().replace("\"daysText\":\"每周5天\"",
                "\"daysText\":\"每天工作8小时\"")));
        JobExtractionService.Result r = service.extract(page);

        assertNull(r.draft().days(), "每日工时不是每周出勤天数");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("疑似每日工时")));
    }

    @Test
    void 同时出现每周天数与工时时取天数() {
        String page = PAGE + " 每周5天，每天工作8小时";
        aiReply.set(reply(baseJson().replace("\"daysText\":\"每周5天\"",
                "\"daysText\":\"每周5天，每天工作8小时\"")));
        JobExtractionService.Result r = service.extract(page);

        assertEquals(5, r.draft().days());
        assertTrue(r.warnings().isEmpty(), "取到明确天数时不应误报：" + r.warnings());
    }

    @Test
    void 日薪原文按日薪类型填入() {
        String page = PAGE + " 200~300元/天";
        aiReply.set(reply(baseJson().replace("\"salaryText\":\"6-8K\"",
                "\"salaryText\":\"200~300元/天\"")));
        JobExtractionService.Result r = service.extract(page);

        assertEquals("DAILY", r.draft().salaryType());
        assertEquals(200, r.draft().salaryMin());
        assertEquals(300, r.draft().salaryMax());
        assertEquals(200, r.draft().dailyMin());
        assertEquals(300, r.draft().dailyMax());
    }

    @Test
    void 技能白名单只保留页面中出现的项() {
        aiReply.set(reply(baseJson().replace(
                "Java, Spring Boot, MySQL, Redis", "C++,Kubernetes,MySQL")));
        JobExtractionService.Result r = service.extract(PAGE);

        assertEquals("MySQL", r.draft().skills());
        assertEquals(2, r.warnings().stream().filter(w -> w.contains("未在页面文本中找到，已丢弃")).count());
    }

    @Test
    void 空文本和纯脚本内容被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service.extract(null));
        assertThrows(IllegalArgumentException.class, () -> service.extract("   "));
        assertThrows(IllegalArgumentException.class,
                () -> service.extract("<script>alert('x')</script><style>body{}</style>"));
        assertEquals(0, saved.get(), "没有调用模型就不应记账");
    }

    @Test
    void 纯网址输入被拒绝且不调用模型() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.extract("https://www.zhipin.com/job_detail/xxx.html?securityId=abc&ka=company"));
        assertTrue(e.getMessage().contains("不抓取网页"));
        assertTrue(e.getMessage().contains("抓取脚本"));
        assertEquals(0, saved.get(), "网址输入不应触发模型调用与记账");
    }

    @Test
    void 额度不足返回空草稿而不阻塞手填() {
        usedCost.set(new BigDecimal("0.1"));
        aiReply.set(reply(baseJson()));
        JobExtractionService.Result r = service.extract(PAGE);

        assertTrue(r.quotaExceeded());
        assertEquals("", r.draft().company());
        assertEquals("", r.draft().skills());
        assertTrue(r.warnings().get(0).contains("默认额度已用完"));
        assertEquals(0, saved.get());
    }

    @Test
    void 模型未配置给出明确提示且不记账() {
        aiReply.set(null);
        JobExtractionService.Result r = service.extract(PAGE);

        assertTrue(r.warnings().get(0).contains("请先在设置页配置模型"));
        assertEquals("", r.draft().company());
        assertEquals(0, saved.get());
    }

    @Test
    void BYO模式不产生平台费用() {
        quota = new AiQuotaService(settingsWith(snapshot(true)), repositoryStub());
        service = new JobExtractionService(aiStub(), quota, new MetaParser());
        aiReply.set(reply(baseJson()));

        JobExtractionService.Result r = service.extract(PAGE);

        assertEquals("某科技有限公司", r.draft().company());
        assertEquals(1, saved.get());
        assertEquals("BYO", usages.get(0).getMode());
        assertEquals(0, BigDecimal.ZERO.compareTo(usages.get(0).getCostCny()));
        assertEquals(0, BigDecimal.ZERO.compareTo(usedCost.get()));
    }
}
