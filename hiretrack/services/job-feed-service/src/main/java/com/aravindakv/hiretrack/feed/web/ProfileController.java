package com.aravindakv.hiretrack.feed.web;

import com.aravindakv.hiretrack.common.web.ApiException;
import com.aravindakv.hiretrack.common.web.CurrentUser;
import com.aravindakv.hiretrack.feed.config.FeedProperties;
import com.aravindakv.hiretrack.feed.domain.RunSchedule;
import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import com.aravindakv.hiretrack.feed.persistence.SearchProfileRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;

@RestController
public class ProfileController {

    public record ProfileRequest(
            @NotBlank @Size(max = 100) String keywords,
            @NotBlank @Pattern(regexp = "^[a-zA-Z]{2}$") String country,
            @NotBlank @Size(max = 100) String location,
            @Min(5) @Max(100) int radiusKm,
            boolean remoteOk,
            boolean enabled,
            @NotBlank String timezone) {}

    public record ProfileResponse(String keywords, String country, String location, int radiusKm, boolean remoteOk,
                                  boolean enabled, String timezone, Instant nextRunAt, Instant lastRunAt, String lastError) {
        static ProfileResponse of(SearchProfile p) {
            return new ProfileResponse(p.getKeywords(), p.getCountry(), p.getLocation(), p.getRadiusKm(), p.isRemoteOk(),
                    p.isEnabled(), p.getTimezone(), p.getNextRunAt(), p.getLastRunAt(), p.getLastError());
        }
    }

    private final SearchProfileRepository profiles;
    private final FeedProperties props;
    private final Clock clock;

    public ProfileController(SearchProfileRepository profiles, FeedProperties props, Clock clock) {
        this.profiles = profiles;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/search-profile")
    @Transactional(readOnly = true)
    ProfileResponse get(@CurrentUser long userId) {
        return profiles.findById(userId).map(ProfileResponse::of)
                .orElseThrow(() -> ApiException.notFound("Search profile"));
    }

    /** PUT is naturally idempotent: the same body twice gives the same profile. */
    @PutMapping("/search-profile")
    @Transactional
    ProfileResponse put(@CurrentUser long userId, @Valid @RequestBody ProfileRequest req) {
        ZoneId zone;
        try {
            zone = ZoneId.of(req.timezone());
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE", "timezone must be an IANA zone such as Asia/Kolkata.");
        }
        SearchProfile p = profiles.findById(userId).orElseGet(() -> new SearchProfile(userId));
        p.update(req.keywords(), req.country(), req.location(), req.radiusKm(), req.remoteOk(), req.enabled(),
                zone.getId(), RunSchedule.next(clock.instant(), zone, props.feed().runHour(), userId, props.feed().jitterMinutes()));
        return ProfileResponse.of(profiles.save(p));
    }
}