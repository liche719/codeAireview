package com.codepilot.module.command.git;

import java.util.List;

record ApplyPatchHunkApplication(List<String> lines, int nextSearchFrom) {
}
