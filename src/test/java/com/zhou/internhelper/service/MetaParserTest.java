package com.zhou.internhelper.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 薪资/时长解析器契约测试。
 * 薪资样本来自第三轮测试报告 N-09，固化期望值防止回归。
 */
class MetaParserTest {

    private final MetaParser parser = new MetaParser();

    private void assertRange(String salary, int low, int high) {
        int[] r = parser.parseDailyRange(salary);
        assertNotNull(r, "应能解析：" + salary);
        assertArrayEquals(new int[]{low, high}, r, "薪资：" + salary);
    }

    @Test
    void 报告十一薪资样本全部符合期望() {
        assertRange("200-300元/天", 200, 300);
        assertRange("6-8K", 276, 368);
        assertRange("8000-12000元/月", 368, 552);
        assertRange("7-9K·13薪", 322, 414);
        assertRange("200~350元/天", 200, 350);          // 波浪号
        assertRange("300元/天-400元/天", 300, 400);      // 重复单位
        assertRange("300-200元/天", 200, 300);          // 倒挂交换
        assertRange("1-2万/月", 460, 920);              // 万/月
        assertRange("日薪250元", 250, 250);             // 日薪前缀
        assertRange("12K", 552, 552);                   // 单值 K
        assertNull(parser.parseDailyRange("面议"));
    }

    @Test
    void 薪数后缀不被误当区间符() {
        assertRange("20-40K·15薪", 920, 1839);
        assertRange("7K·13薪", 322, 322);
    }

    @Test
    void 中文全角区间符也支持() {
        assertRange("200～350元/天", 200, 350);
        assertRange("200－350元/天", 200, 350);
    }

    @Test
    void 出勤多种写法都识别() {
        MetaParser.Schedule a = parser.parseSchedule("5天/周、3个月");
        assertEquals(5, a.days());
        assertEquals(3, a.months());

        MetaParser.Schedule b = parser.parseSchedule("每周5天，实习6个月");
        assertEquals(5, b.days(), "每周5天应识别");
        assertEquals(6, b.months());

        MetaParser.Schedule c = parser.parseSchedule("每周出勤4天，3个月");
        assertEquals(4, c.days());

        MetaParser.Schedule d = parser.parseSchedule("4天每周");
        assertEquals(4, d.days());
    }

    @Test
    void 出勤与月数仍受硬上限收敛() {
        MetaParser.Schedule s = parser.parseSchedule("每周10天，实习20个月");
        assertEquals(7, s.days(), "天数收敛到 7");
        assertEquals(20, s.months(), "解析层保留到 24 上限内，20 合法");
    }

    @Test
    void 解析不出返回null值对象() {
        MetaParser.Schedule s = parser.parseSchedule("熟悉SpringBoot，无时长信息");
        assertNull(s.days());
        assertNull(s.months());
        assertNull(parser.parseDailyRange(""));
        assertNull(parser.parseDailyRange(null));
    }
}
