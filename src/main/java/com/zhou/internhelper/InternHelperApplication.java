package com.zhou.internhelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 实习投递助手
 *
 * 功能：岗位库管理、打招呼话术按岗位自动生成、投递记录、转化统计。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class InternHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(InternHelperApplication.class, args);
    }
}
