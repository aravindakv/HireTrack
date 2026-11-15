package com.aravindakv.hiretrack.analytics.web;

import com.aravindakv.hiretrack.analytics.service.DashboardService;
import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;

@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    DashboardService.Dashboard dashboard(@CurrentUser long userId, @RequestParam(defaultValue = "UTC") String tz) {
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "tz must be an IANA timezone such as Asia/Kolkata.");
        }
        return service.get(userId, zone);
    }
}