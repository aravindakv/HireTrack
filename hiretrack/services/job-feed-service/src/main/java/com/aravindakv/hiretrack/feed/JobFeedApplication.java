package com.aravindakv.hiretrack.feed;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableHiretrackSecurity
@ConfigurationPropertiesScan
@EnableScheduling
public class JobFeedApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobFeedApplication.class, args);
    }
}
