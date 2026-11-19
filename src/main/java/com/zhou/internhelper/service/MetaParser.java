package com.zhou.internhelper.service;

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

    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*天\\s*/\\s*周");
    private static final Pattern MONTHS_PATTERN = Pattern.compile("(\\d+)\\s*个月");

    /** 元/天，如 200-400元/天、150元/天 */
    private static final Pattern PER_DAY = Pattern.compile("(\\d+)\\s*(?:-\\s*(\\d+))?\\s*元\\s*/\\s*天");
    /** K/月，如 4-6K、20-40K·15薪 */
    private static final Pattern PER_MONTH_K = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)\\s*[Kk]");
    /** 元/月 */
    private static final Pattern PER_MONTH_YUAN = Pattern.compile("(\\d+)\\s*(?:-\\s*(\\d+))?\\s*元\\s*/\\s*月");

    /** 月薪折算日薪用的工作日数 */
    private static final double WORK_DAYS_PER_MONTH = 21.75;

    public record Schedule(Integer months, Integer days) {
    }

    /** 解析实习时长和出勤天数，解析不出返回 null */
    public Schedule parseSchedule(String text) {
        if (text == null || text.isBlank()) {
            return new Schedule(null, null);
        }
        Integer months = null;
        Integer days = null;
        Matcher dm = DAYS_PATTERN.matcher(text);
        if (dm.find()) {
            days = clamp(Integer.parseInt(dm.group(1)), 1, 7);
        }
        Matcher mm = MONTHS_PATTERN.matcher(text);
        if (mm.find()) {
            months = clamp(Integer.parseInt(mm.group(1)), 1, 24);
        }
        return new Schedule(months, days);
    }

    /** 工资折算成日薪区间 [最低, 最高]（元/天），解析不出返回 null */
    public int[] parseDailyRange(String salary) {
        if (salary == null || salary.isBlank()) {
            return null;
        }
        String s = salary.replace("·", "-").replace(" ", "");

        Matcher m = PER_DAY.matcher(s);
        if (m.find()) {
            int low = Integer.parseInt(m.group(1));
            int high = m.group(2) == null ? low : Integer.parseInt(m.group(2));
            return new int[]{low, Math.max(low, high)};
        }
        m = PER_MONTH_K.matcher(s);
        if (m.find()) {
            double low = Double.parseDouble(m.group(1)) * 1000;
            double high = Double.parseDouble(m.group(2)) * 1000;
            return new int[]{(int) Math.round(low / WORK_DAYS_PER_MONTH),
                    (int) Math.round(Math.max(low, high) / WORK_DAYS_PER_MONTH)};
        }
        m = PER_MONTH_YUAN.matcher(s);
        if (m.find()) {
            double low = Double.parseDouble(m.group(1));
            double high = m.group(2) == null ? low : Double.parseDouble(m.group(2));
            return new int[]{(int) Math.round(low / WORK_DAYS_PER_MONTH),
                    (int) Math.round(Math.max(low, high) / WORK_DAYS_PER_MONTH)};
        }
        if (s.matches(".*\\d+(\\.\\d+)?[Kk].*")) {
            Matcher single = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[Kk]").matcher(s);
            if (single.find()) {
                int v = (int) Math.round(Double.parseDouble(single.group(1)) * 1000 / WORK_DAYS_PER_MONTH);
                return new int[]{v, v};
            }
        }
        return null;
    }

    /** 兼容旧调用：取区间下限 */
    public Integer parseDailyPay(String salary) {
        int[] range = parseDailyRange(salary);
        return range == null ? null : range[0];
    }

    private Integer clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
