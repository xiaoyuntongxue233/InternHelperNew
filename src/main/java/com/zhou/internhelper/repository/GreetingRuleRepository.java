package com.zhou.internhelper.repository;

import com.zhou.internhelper.entity.GreetingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GreetingRuleRepository extends JpaRepository<GreetingRule, Long> {

    List<GreetingRule> findByEnabledTrueOrderByPriorityAsc();
}
