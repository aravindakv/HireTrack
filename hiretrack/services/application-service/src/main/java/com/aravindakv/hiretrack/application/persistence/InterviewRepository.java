package com.aravindakv.hiretrack.application.persistence;

import com.aravindakv.hiretrack.application.domain.Interview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InterviewRepository extends JpaRepository<Interview, Long> {

    List<Interview> findByApplicationIdOrderByScheduledAtAsc(Long applicationId);

    /** Interviews have no user_id: ownership is checked through the parent application (spec 7.3). */
    @Query("""
            SELECT i FROM Interview i
            WHERE i.id = :id
              AND EXISTS (SELECT 1 FROM JobApplication a WHERE a.id = i.applicationId AND a.userId = :userId)""")
    Optional<Interview> findOwned(Long id, Long userId);
}