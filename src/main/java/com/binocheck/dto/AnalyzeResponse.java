package com.binocheck.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyzeResponse {
    private Long id;
    private String repoUrl;
    private String owner;
    private String repoName;
    private String description;
    private String primaryLanguage;
    private Integer starsCount;
    private String analysisResult;
    private String fileTreeJson;
    private LocalDateTime analyzedAt;
}
