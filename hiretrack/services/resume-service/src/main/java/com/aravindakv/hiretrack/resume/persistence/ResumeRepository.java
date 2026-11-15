package com.aravindakv.hiretrack.resume.persistence;

import com.aravindakv.hiretrack.resume.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    List<Resume> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    Optional<Resume> findByUserIdAndActiveTrue(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE resumes SET is_active = false WHERE user_id = :userId AND is_active", nativeQuery = true)
    int deactivateAll(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE resumes SET is_active = true WHERE id = :id AND user_id = :userId", nativeQuery = true)
    int activate(Long id, Long userId);
}