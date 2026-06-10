package com.legacy.modernizer.agent;

/**
 * A single structured error extracted from {@code mvn compile} stderr.
 *
 * @param file    simple filename (e.g. {@code "OwnerService.java"})
 * @param line    source line number reported by javac
 * @param message raw javac error message (e.g. {@code "cannot find symbol"})
 */
public record CompilerError(String file, int line, String message) {}
