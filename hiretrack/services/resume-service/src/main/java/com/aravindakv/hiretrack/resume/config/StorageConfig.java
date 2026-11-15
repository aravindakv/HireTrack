package com.aravindakv.hiretrack.resume.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Configuration
public class StorageConfig {

    @Bean
    S3Client s3Client(ResumeProperties props) {
        ResumeProperties.Storage s = props.storage();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s.region()))
                .forcePathStyle(s.pathStyle());
        if (StringUtils.hasText(s.endpoint())) {
            builder.endpointOverride(URI.create(s.endpoint()));
        }
        builder.credentialsProvider(StringUtils.hasText(s.accessKey())
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKey(), s.secretKey()))
                : DefaultCredentialsProvider.create());
        return builder.build();
    }
}