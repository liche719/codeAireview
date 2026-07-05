package com.codepilot.module.command.git;

import java.util.ArrayList;
import java.util.List;

record ApplyPatchSection(ApplyPatchSectionType type, String path, List<List<String>> blocks) {

    ApplyPatchSection(ApplyPatchSectionType type, String path) {
        this(type, path, new ArrayList<>());
    }
}
