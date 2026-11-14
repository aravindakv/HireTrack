package com.aravindakv.hiretrack.application;

import com.aravindakv.hiretrack.common.web.EnableHiretrackSecurity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableHiretrackSecurity
@ConfigurationPropertiesScan
public class ApplicationServiceApp {
    public static void main(String[] args) {
        SpringApplication.run(ApplicationServiceApp.class, args);
    }
}
