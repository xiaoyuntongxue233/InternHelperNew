package com.zhou.internhelper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从岗位文本里抽取结构化字段：
 * - 实习时长（月）、每周出勤（天）
 * - 薪资折算成日薪（元/天），方便排序比较
 */
@Service
public class MetaParser {

    private static final Logger log = LoggerFactory.getLogger(MetaParser.class);

    /** 出勤：兼容 5天/周、每周5天、每周出勤5天、5天每周 */
    private static final Pattern[] DAY_PATTERNS = {
            Pattern.compile("(\\d+)\\s*天\\s*/\\s*周"),
            Pattern.compile("每\\s*周\\s*(?:出勤)?\\s*(\\d+)\\s*天"),
            Pattern.compile("(\\d+)\\s*天\\s*每\\s*周")
    };
    private static final Pattern MONTHS_PATTERN = Pattern.compile("(\\d+)\\s*个月");

    /** 日薪前缀，如 日薪250元 / 日薪200-300 */
    private static final Pattern DAILY_PREFIX = Pattern.compile(
            "日薪(\\d+(?:\\.\\d+)?)(?:[^\\d]*-(\\d+(?:\\.\\d+)?))?");
    /** 归一化后的区间：数字 - 数字，中间只允许非数字字符（如 元/天-、万/月 等） */
    private static final Pattern RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)[^\\d]*-[^\\d]*(\\d+(?:\\.\\d+)?)");
    /** 单值：取第一个数字 */
    private static final Pattern SINGLE = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    /** 薪资里的薪数后缀，如 ·13薪、/15薪、-13薪（不是区间符，需先剥离） */
    private static final Pattern SALARY_MONTHS = Pattern.compile("[·•/\\-]?\\d+薪");

    /** 月薪折算日薪用的工作日数 */
    private static final double WORK_DAYS_PER_MONTH = 21.75;

    public record Schedule(Integer months, Integer days) {
    }

    /** 解析实习时长和出勤天数，解析不出返回 null */
    public Schedule parseSchedule(String text) {
        if (text == null || text.isBlank()) {
            return new Schedule(null, null);
        }
        Integer days = null;
        for (Pattern pattern : DAY_PATTERNS) {
            Matcher dm = pattern.matcher(text);
            if (dm.find()) {
                days = clamp((int) Double.parseDouble(dm.group(1)), 1, 7);
                break;
            }
        }
        Integer months = null;
        Matcher mm = MONTHS_PATTERN.matcher(text);
        if (mm.find()) {
            months = clamp((int) Double.parseDouble(mm.group(1)), 1, 24);
        }
        return new Schedule(months, days);
    }

    /**
     * 工资折算成日薪区间 [最低, 最高]（元/天），解析不出返回 null。
     * 归一化顺序：剥离薪数后缀 → 统一区间符 → 按单位分支提取 → 倒挂交换。
     */
    public int[] parseDailyRange(String salary) {
        if (salary == null || salary.isBlank()) {
            return null;
        }
        String s = normalize(salary);
        if (s.isEmpty()) {
            return null;
        }

        // 1) 日薪前缀（优先级最高）：日薪250元、日薪200-300
        Matcher m = DAILY_PREFIX.matcher(s);
        if (m.find()) {
            double low = num(m.group(1));
            double high = m.group(2) == null ? low : num(m.group(2));
            return ordered(low, high);
        }
        // 2) 带「天」标识：200-300元/天、300元/天-400元/天、200~350元/天
        if (s.contains("天")) {
            if ((m = RANGE.matcher(s)).find()) return ordered(num(m.group(1)), num(m.group(2)));
            if ((m = SINGLE.matcher(s)).find()) return single(num(m.group(1)));
        }
        // 3) 万/月：1-2万/月 → ×10000/21.75
        if (s.contains("万")) {
            if ((m = RANGE.matcher(s)).find()) return scaled(num(m.group(1)), num(m.group(2)), 10000);
            if ((m = SINGLE.matcher(s)).find()) return singleScaled(num(m.group(1)), 10000);
        }
        // 4) K：6-8K、7K·13薪(已剥离薪数)、12K → ×1000/21.75
        if (s.contains("k") || s.contains("K")) {
            if ((m = RANGE.matcher(s)).find()) return scaled(num(m.group(1)), num(m.group(2)), 1000);
            if ((m = SINGLE.matcher(s)).find()) return singleScaled(num(m.group(1)), 1000);
        }
        // 5) 元/月：8000-12000元/月 → /21.75
        if (s.contains("月")) {
            if ((m = RANGE.matcher(s)).find()) return scaled(num(m.group(1)), num(m.group(2)), 1);
            if ((m = SINGLE.matcher(s)).find()) return singleScaled(num(m.group(1)), 1);
        }
        return null;
    }

    /** 兼容旧调用：取区间下限 */
    public Integer parseDailyPay(String salary) {
        int[] range = parseDailyRange(salary);
        return range == null ? null : range[0];
    }

    /** 归一化：去空白、剥离薪数后缀、统一各种区间符为 '-' */
    private String normalize(String salary) {
        String s = salary.replaceAll("\\s", "");
        s = SALARY_MONTHS.matcher(s).replaceAll("");
        return s.replace('~', '-').replace('～', '-').replace('－', '-')
                .replace('—', '-').replace('〜', '-');
    }

    private double num(String s) {
        return Double.parseDouble(s);
    }

    /** 保证 [low, high] 语义；倒挂时交换并记日志，不再静默归一为单值 */
    private int[] ordered(double a, double b) {
        if (a > b) {
            log.warn("薪资区间上下限倒挂（{} > {}），已自动交换", a, b);
            double t = a;
            a = b;
            b = t;
        }
        return new int[]{(int) Math.round(a), (int) Math.round(b)};
    }

    private int[] single(double v) {
        int x = (int) Math.round(v);
        return new int[]{x, x};
    }

    private int[] scaled(double low, double high, int factor) {
        return ordered(low * factor / WORK_DAYS_PER_MONTH, high * factor / WORK_DAYS_PER_MONTH);
    }

    private int[] singleScaled(double v, int factor) {
        int x = (int) Math.round(v * factor / WORK_DAYS_PER_MONTH);
        return new int[]{x, x};
    }

    private Integer clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
