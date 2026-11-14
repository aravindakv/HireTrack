package com.aravindakv.hiretrack.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@SpringBootApplication
@EnableHiretrackSecurity
public class WebTestApp {

    @RestController
    static class ProbeController {

        @GetMapping("/me")
        Map<String, Long> me(@CurrentUser long userId) {
            return Map.of("userId", userId);
        }

        @GetMapping("/boom")
        void boom() {
            throw ApiException.conflict("THING_TAKEN", "That thing is taken.");
        }

        @PostMapping("/things")
        Map<String, String> create(@Valid @RequestBody Thing thing) {
            return Map.of("name", thing.name());
        }

        @GetMapping("/internal/ping")
        Map<String, String> ping() {
            return Map.of("pong", "yes");
        }
    }

    record Thing(@NotBlank String name) {}
}