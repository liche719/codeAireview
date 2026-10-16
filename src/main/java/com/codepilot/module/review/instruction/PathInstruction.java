package com.codepilot.module.review.instruction;

/**
 * 一条路径级审查指令：匹配某个 glob 的文件时，把 {@code instruction} 注入该文件的 prompt。
 *
 * @param glob        形如 {@code src/main/java/**}{@code /*.java} 的匹配模式
 * @param instruction 该路径下要遵守的审查要求
 */
public record PathInstruction(String glob, String instruction) {
}
