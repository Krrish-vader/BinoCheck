package com.binocheck.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TreeEntry {
    private String path;
    private String type; // "blob" or "tree"
    private Long size;
}
