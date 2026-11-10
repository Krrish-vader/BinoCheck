package com.binocheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    @NotNull(message = "repoId is required.")
    private Long repoId;

    @NotBlank(message = "Message cannot be empty.")
    @Size(max = 4000, message = "Message is too long (max 4000 characters).")
    private String message;
}
