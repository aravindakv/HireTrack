package com.aravindakv.hiretrack.feed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "search_profiles")
public class SearchProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false) private String keywords;
    @Column(nullable = false) private String country;
    @Column(nullable = false) private String location;
    @Column(name = "radius_km", nullable = false) private int radiusKm;
    @Column(name = "remote_ok", nullable = false) private boolean remoteOk;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private String timezone;
    @Column(name = "query_key", nullable = false) private String queryKey;
    @Column(name = "next_run_at", nullable = false) private Instant nextRunAt;
    @Column(name = "last_run_at") private Instant lastRunAt;
    @Column(name = "last_manual_run_at") private Instant lastManualRunAt;
    @Column(name = "last_error") private String lastError;
    @Version private Long version;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected SearchProfile() {}

    public SearchProfile(Long userId) {
        this.userId = userId;
    }

    public void update(String keywords, String country, String location, int radiusKm, boolean remoteOk,
                       boolean enabled, String timezone, Instant nextRunAt) {
        this.keywords = keywords.trim();
        this.country = country.toLowerCase();
        this.location = location.trim();
        this.radiusKm = radiusKm;
        this.remoteOk = remoteOk;
        this.enabled = enabled;
        this.timezone = timezone;
        this.queryKey = QueryKey.of(country, location, radiusKm, keywords, remoteOk);
        this.nextRunAt = nextRunAt;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public Long getUserId() { return userId; }
    public String getKeywords() { return keywords; }
    public String getCountry() { return country; }
    public String getLocation() { return location; }
    public int getRadiusKm() { return radiusKm; }
    public boolean isRemoteOk() { return remoteOk; }
    public boolean isEnabled() { return enabled; }
    public String getTimezone() { return timezone; }
    public String getQueryKey() { return queryKey; }
    public Instant getNextRunAt() { return nextRunAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public Instant getLastManualRunAt() { return lastManualRunAt; }
    public String getLastError() { return lastError; }
}