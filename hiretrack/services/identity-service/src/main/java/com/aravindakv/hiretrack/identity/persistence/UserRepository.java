package com.aravindakv.hiretrack.identity.persistence;

import com.aravindakv.hiretrack.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Returns 1 the first time a user is seen on a given day, 0 afterwards. */
    @Modifying
    @Query(value = """
            UPDATE users SET last_seen_on = :today
            WHERE id = :userId AND (last_seen_on IS NULL OR last_seen_on <> :today)""", nativeQuery = true)
    int markSeen(Long userId, LocalDate today);
}