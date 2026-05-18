package com.codepilot.module.tool.dto;

import lombok.Data;

import java.util.List;

@Data
public class ToolCheckRequest {

    private String filePath;

    private String patch;

    private List<String> allChangedFiles;
}
