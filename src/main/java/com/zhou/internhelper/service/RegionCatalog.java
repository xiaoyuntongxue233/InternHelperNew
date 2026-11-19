package com.zhou.internhelper.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 城市字典：把「广州海珠区」这类混合地点串拆成 省 / 市 / 区。
 *
 * 目前用内置字典（覆盖常见城市），够用于本项目的岗位库；
 * 后续可以换成数据库表或第三方行政区划数据。
 */
@Service
public class RegionCatalog {

    /** 城市 → 省（直辖市用同名） */
    private static final Map<String, String> CITY_TO_PROVINCE = new LinkedHashMap<>();

    static {
        put("广东", "广州", "深圳", "东莞", "佛山", "珠海", "中山", "惠州", "江门", "肇庆", "汕头", "清远", "茂名", "湛江");
        put("北京", "北京");
        put("上海", "上海");
        put("天津", "天津");
        put("重庆", "重庆");
        put("浙江", "杭州", "宁波", "温州", "嘉兴", "绍兴", "金华");
        put("江苏", "南京", "苏州", "无锡", "常州", "南通", "徐州");
        put("四川", "成都", "绵阳");
        put("湖北", "武汉");
        put("湖南", "长沙");
        put("陕西", "西安");
        put("山东", "济南", "青岛", "烟台", "威海", "潍坊");
        put("辽宁", "沈阳", "大连");
        put("福建", "厦门", "福州", "泉州");
        put("河南", "郑州");
        put("安徽", "合肥");
        put("江西", "南昌", "赣州");
        put("云南", "昆明");
        put("贵州", "贵阳");
        put("河北", "石家庄", "保定");
        put("山西", "太原");
        put("吉林", "长春");
        put("黑龙江", "哈尔滨");
        put("甘肃", "兰州");
        put("广西", "南宁", "桂林");
        put("海南", "海口", "三亚");
    }

    private static void put(String province, String... cities) {
        for (String city : cities) {
            CITY_TO_PROVINCE.put(city, province);
        }
    }

    /** 解析结果 */
    public record Region(String province, String city, String district) {
    }

    /**
     * 解析地点串，例如：
     * 广州海珠区 -> 广东 / 广州 / 海珠区
     * 北京海淀区 -> 北京 / 北京 / 海淀区
     * 深圳        -> 广东 / 深圳 / null
     */
    public Region parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Region(null, null, null);
        }
        String text = raw.trim().replace("·", "").replace(" ", "");

        // 先试「省名开头」，比如 广东省广州市
        for (Map.Entry<String, String> e : new LinkedHashMap<>(CITY_TO_PROVINCE).entrySet()) {
            String city = e.getKey();
            if (text.startsWith(city)) {
                String rest = text.substring(city.length()).trim();
                return new Region(e.getValue(), city, rest.isEmpty() ? null : rest);
            }
        }
        // 再试「省名开头 + 城市在中间」：广东省广州
        for (String province : new java.util.LinkedHashSet<>(CITY_TO_PROVINCE.values())) {
            if (text.startsWith(province)) {
                String rest = text.substring(province.length()).trim();
                for (String city : citiesOf(province)) {
                    if (rest.startsWith(city)) {
                        String district = rest.substring(city.length()).trim();
                        return new Region(province, city, district.isEmpty() ? null : district);
                    }
                }
                return new Region(province, rest.isEmpty() ? null : rest, null);
            }
        }
        return new Region(null, text, null);
    }

    public Optional<String> provinceOfCity(String city) {
        return Optional.ofNullable(CITY_TO_PROVINCE.get(city));
    }

    public List<String> citiesOf(String province) {
        List<String> list = new ArrayList<>();
        CITY_TO_PROVINCE.forEach((city, prov) -> {
            if (prov.equals(province)) {
                list.add(city);
            }
        });
        return list;
    }

    /** 省 → 城市列表，按省名排序 */
    public Map<String, List<String>> tree() {
        Map<String, List<String>> map = new TreeMap<>();
        CITY_TO_PROVINCE.forEach((city, prov) -> map.computeIfAbsent(prov, k -> new ArrayList<>()).add(city));
        return map;
    }
}
