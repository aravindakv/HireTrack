package com.aravindakv.hiretrack.resume.storage;

import com.aravindakv.hiretrack.resume.config.ResumeProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.UUID;

@Component
public class ResumeStorage {

    private static final Logger log = LoggerFactory.getLogger(ResumeStorage.class);

    private final S3Client s3;
    private final ResumeProperties.Storage props;

    public ResumeStorage(S3Client s3, ResumeProperties props) {
        this.s3 = s3;
        this.props = props.storage();
    }

    @PostConstruct
    void createBucketIfAllowed() {
        if (!props.createBucket()) return;
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Created bucket {}", props.bucket());
        }
    }

    /** @return the object key */
    public String storePdf(long userId, byte[] bytes) {
        String key = "resumes/" + userId + "/" + UUID.randomUUID() + ".pdf";
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(props.bucket()).key(key).contentType("application/pdf");
        if (props.serverSideEncryption()) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(request.build(), RequestBody.fromBytes(bytes));
        return key;
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
        } catch (RuntimeException e) {
            log.warn("Could not delete orphaned object {}: {}", key, e.getMessage());
        }
    }
}