package com.aravindakv.hiretrack.analytics;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableHiretrackSecurity
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
