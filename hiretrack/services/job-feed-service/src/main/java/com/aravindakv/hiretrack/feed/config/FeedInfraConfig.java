package com.aravindakv.hiretrack.feed.config;

import com.aravindakv.hiretrack.common.events.Topics;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class FeedInfraConfig {

    @Bean
    LockProvider lockProvider(JdbcTemplate jdbc) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).usingDbTime().build());
    }

    @Bean
    NewTopic feedCommands(@Value("${hiretrack.kafka.partitions}") int p, @Value("${hiretrack.kafka.replicas}") int r) {
        return TopicBuilder.name(Topics.FEED_COMMANDS).partitions(p).replicas(r).build();
    }

    @Bean
    NewTopic feedEvents(@Value("${hiretrack.kafka.partitions}") int p, @Value("${hiretrack.kafka.replicas}") int r) {
        return TopicBuilder.name(Topics.FEED_EVENTS).partitions(p).replicas(r).build();
    }
}