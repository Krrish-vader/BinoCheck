package com.binocheck.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

@Entity
@Table(name = "analyzed_repos", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "repo_url"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyzedRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String repoUrl;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repoName;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String primaryLanguage;

    private Integer starsCount;

    @Column(columnDefinition = "TEXT")
    private String analysisResult;

    @Column(columnDefinition = "TEXT")
    private String fileTreeJson;

    @Column(nullable = false)
    private LocalDateTime analyzedAt;
}
