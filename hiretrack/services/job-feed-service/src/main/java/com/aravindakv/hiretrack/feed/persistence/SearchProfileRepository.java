package com.aravindakv.hiretrack.feed.persistence;

import com.aravindakv.hiretrack.feed.domain.SearchProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchProfileRepository extends JpaRepository<SearchProfile, Long> {}