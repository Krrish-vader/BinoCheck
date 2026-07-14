package com.binocheck.repository;

import com.binocheck.entity.AnalyzedRepo;
import com.binocheck.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByRepoOrderBySentAtAsc(AnalyzedRepo repo);
}
