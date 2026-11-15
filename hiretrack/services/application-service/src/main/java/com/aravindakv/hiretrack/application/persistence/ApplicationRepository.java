package com.aravindakv.hiretrack.application.persistence;

import com.aravindakv.hiretrack.application.domain.JobApplication;
import com.aravindakv.hiretrack.application.domain.SourceType;
import com.aravindakv.hiretrack.application.domain.Stage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<JobApplication, Long> {

    List<JobApplication> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<JobApplication> findByUserIdAndStageOrderByUpdatedAtDesc(Long userId, Stage stage);

    Optional<JobApplication> findByIdAndUserId(Long id, Long userId);

    Optional<JobApplication> findByUserIdAndSourceTypeAndSourceId(Long userId, SourceType sourceType, String sourceId);
}