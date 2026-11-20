package com.zhou.internhelper.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 地点解析契约测试：覆盖省/市/区前缀残留问题 */
class RegionCatalogTest {

    private final RegionCatalog catalog = new RegionCatalog();

    private void assertRegion(String raw, String province, String city, String district) {
        RegionCatalog.Region r = catalog.parse(raw);
        assertAll("解析 " + raw,
                () -> assertEquals(province, r.province(), "province"),
                () -> assertEquals(city, r.city(), "city"),
                () -> assertEquals(district, r.district(), "district"));
    }

    @Test
    void 城市加区县() {
        assertRegion("广州海珠区", "广东", "广州", "海珠区");
    }

    @Test
    void 城市带市字加区县不留前缀() {
        assertRegion("广州市海珠区", "广东", "广州", "海珠区");
    }

    @Test
    void 省加市带市字加区县() {
        assertRegion("广东省广州市海珠区", "广东", "广州", "海珠区");
    }

    @Test
    void 省加市不加市字() {
        assertRegion("广东广州天河区", "广东", "广州", "天河区");
    }

    @Test
    void 直辖市海淀区() {
        assertRegion("北京海淀区", "北京", "北京", "海淀区");
    }

    @Test
    void 直辖市带市字() {
        assertRegion("北京市海淀区", "北京", "北京", "海淀区");
    }

    @Test
    void 只有城市名() {
        assertRegion("深圳", "广东", "深圳", null);
    }

    @Test
    void 带间隔符与空格() {
        assertRegion("上海市 · 浦东新区", "上海", "上海", "浦东新区");
    }
}
