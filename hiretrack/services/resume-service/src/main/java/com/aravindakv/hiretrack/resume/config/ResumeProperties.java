package com.aravindakv.hiretrack.resume.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hiretrack")
public record ResumeProperties(Resume resume, Storage storage) {

    public record Resume(int maxCharacters) {}

    public record Storage(String endpoint, String region, String bucket, String accessKey, String secretKey,
                          boolean pathStyle, boolean serverSideEncryption, boolean createBucket) {}
}