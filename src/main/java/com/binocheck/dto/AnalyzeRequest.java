package com.binocheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyzeRequest {
    @NotBlank(message = "Please enter a GitHub repository URL.")
    @Size(max = 300, message = "The repository URL is too long.")
    private String repoUrl;
}
