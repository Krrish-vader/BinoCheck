package com.binocheck.repository;

import com.binocheck.entity.AnalyzedRepo;
import com.binocheck.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalyzedRepoRepository extends JpaRepository<AnalyzedRepo, Long> {
    List<AnalyzedRepo> findByUserOrderByAnalyzedAtDesc(User user);
    Optional<AnalyzedRepo> findByRepoUrlAndUser(String repoUrl, User user);
}
