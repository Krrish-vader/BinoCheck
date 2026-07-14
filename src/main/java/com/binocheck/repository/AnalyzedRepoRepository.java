package com.binocheck.repository;

import com.binocheck.entity.AnalyzedRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AnalyzedRepoRepository extends JpaRepository<AnalyzedRepo, Long> {
    Optional<AnalyzedRepo> findByRepoUrl(String repoUrl);
}
