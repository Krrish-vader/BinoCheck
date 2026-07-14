package com.binocheck.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubTreeResponse {
    private List<TreeEntry> tree;
    private Boolean truncated;
}
