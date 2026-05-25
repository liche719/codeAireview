package com.codepilot.module.tool.dto;

import lombok.Data;

import java.util.List;

@Data
public class ToolCheckRequest {

    private String filePath;

    private String patch;

    private List<String> allChangedFiles;

    private String reviewContextText;

    public static ToolCheckRequest of(
            String filePath,
            String patch,
            List<String> allChangedFiles,
            String reviewContextText
    ) {
        ToolCheckRequest request = new ToolCheckRequest();
        request.setFilePath(filePath);
        request.setPatch(patch);
        request.setAllChangedFiles(allChangedFiles == null ? List.of() : allChangedFiles);
        request.setReviewContextText(reviewContextText);
        return request;
    }
}
