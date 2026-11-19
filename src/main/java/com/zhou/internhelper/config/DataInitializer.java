package com.zhou.internhelper.config;

import com.zhou.internhelper.entity.GreetingRule;
import com.zhou.internhelper.repository.GreetingRuleRepository;
import com.zhou.internhelper.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 初始化话术规则（只在表为空时插入，避免覆盖手工修改）+ 回填历史岗位的省市区
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    @Transactional
    CommandLineRunner initRules(GreetingRuleRepository repository, JobService jobService) {
        return args -> {
            if (repository.count() == 0) {
                List<GreetingRule> rules = List.of(
                    rule("测试|测开|QA|质量", "熟悉Java，有接口测试、用例设计与缺陷跟踪相关经验", "测试方向", 10),
                    rule("数据开发|大数据|数仓|ETL|BI|Hadoop|Spark|Flink", "熟悉Java与SQL，有MySQL、数据开发相关经验", "数据方向", 20),
                    rule("算法|机器学习|深度学习|NLP|CV|推荐", "熟悉Java，有数据结构与算法、MySQL相关经验", "算法方向", 30),
                    rule("\\bAI\\b|大模型|Agent|智能体|LLM|AIGC|人工智能", "熟悉Java，有Springboot、MySQL相关经验", "AI 方向", 40),
                    rule("Golang|GO开发|Go语言|\\bGo\\b", "熟悉Java与Golang，有Springboot、MySQL相关经验", "Go 方向", 50),
                    rule("前端|全栈|Vue|React", "熟悉Java，有Springboot、MySQL相关经验，了解前端基础", "全栈方向", 60),
                    rule("SpringCloud|微服务|Dubbo|分布式|Nacos", "熟悉Java，有Springboot、SpringCloud、MySQL相关经验", "微服务方向", 70),
                    rule("Redis|缓存|Kafka|RocketMQ|消息队列|MQ", "熟悉Java，有Springboot、MySQL、Redis相关经验", "中间件方向", 80),
                    rule("Linux|运维|部署|Docker|K8s|容器", "熟悉Java，有Springboot、MySQL、Linux相关经验", "运维方向", 90)
            );
            repository.saveAll(rules);
                log.info("初始化话术规则 {} 条", rules.size());
            } else {
                log.info("话术规则已存在 {} 条，跳过初始化", repository.count());
            }

            // 回填历史岗位的 省/市/区 与 时长/日薪
            int fixed = jobService.backfill();
            if (fixed > 0) {
                log.info("回填省市区/时长日薪完成：{} 条", fixed);
            }
        };
    }

    private GreetingRule rule(String pattern, String clause, String direction, int priority) {
        GreetingRule r = new GreetingRule();
        r.setPattern(pattern);
        r.setSkillClause(clause);
        r.setDirection(direction);
        r.setPriority(priority);
        r.setEnabled(true);
        return r;
    }
}
