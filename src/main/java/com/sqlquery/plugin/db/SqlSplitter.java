/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into individual statements.
 *
 * <p>The scanner understands single quotes (including doubled {@code ''} escapes),
 * double quoted identifiers, dollar quoted bodies ({@code $$...$$}, {@code $tag$...$tag$}),
 * line comments ({@code --}) and block comments ({@code /* *}{@code /}) including
 * PostgreSQL's nesting of them. Semicolons inside any of those do not terminate a
 * statement.</p>
 */
public final class SqlSplitter {

    /** One statement found in the script, with the offsets it occupies in the source. */
    public record Statement(@NotNull String sql, int startOffset, int endOffset) {
        /** A compact single line rendering used as a results tab title. */
        public @NotNull String preview(int maxLength) {
            String flat = sql.replaceAll("\\s+", " ").trim();
            if (flat.length() <= maxLength) {
                return flat;
            }
            return flat.substring(0, Math.max(0, maxLength - 1)) + "\u2026";
        }
    }

    /** Convenience for a statement that has not been located in a document. */
    public static @NotNull Statement of(@NotNull String sql) {
        return new Statement(sql, 0, sql.length());
    }

    private SqlSplitter() {
    }

    public static @NotNull List<Statement> split(@NotNull String script) {
        List<Statement> statements = new ArrayList<>();
        int length = script.length();
        int start = -1;
        boolean startPending = false;
        boolean lineComment = false;
        boolean blockComment = false;
        int blockDepth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        String dollarTag = null;

        for (int i = 0; i < length; i++) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '/' && next == '*') {
                    blockDepth++;
                    i++;
                } else if (c == '*' && next == '/') {
                    blockDepth--;
                    i++;
                    if (blockDepth == 0) {
                        blockComment = false;
                    }
                }
                continue;
            }
            if (dollarTag != null) {
                if (c == '$' && script.startsWith(dollarTag, i)) {
                    i += dollarTag.length() - 1;
                    dollarTag = null;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'') {
                    if (next == '\'') {
                        i++; // escaped quote
                    } else {
                        singleQuote = false;
                    }
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"') {
                    if (next == '"') {
                        i++; // escaped identifier quote
                    } else {
                        doubleQuote = false;
                    }
                }
                continue;
            }

            // Not inside any literal or comment.
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                blockDepth = 1;
                i++;
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
                if (start < 0) {
                    startPending = true;
                }
                continue;
            }
            if (c == '"') {
                doubleQuote = true;
                if (start < 0) {
                    startPending = true;
                }
                continue;
            }
            if (c == '$') {
                int tagEnd = dollarTagEnd(script, i);
                if (tagEnd > 0) {
                    dollarTag = script.substring(i, tagEnd);
                    i = tagEnd - 1;
                    if (start < 0) {
                        startPending = true;
                    }
                    continue;
                }
            }
            if (c == ';') {
                addStatement(statements, script, start, i, startPending);
                start = -1;
                startPending = false;
                continue;
            }
            if (!Character.isWhitespace(c) && start < 0) {
                startPending = true;
            }
            if (startPending && start < 0) {
                start = i;
            }
        }

        // Trailing statement without a terminating semicolon.
        addStatement(statements, script, start, length, startPending);
        return statements;
    }

    /**
     * Returns the index just past a dollar quote tag starting at {@code from}, or {@code -1}
     * when the character at {@code from} does not open one.
     */
    private static int dollarTagEnd(@NotNull String script, int from) {
        int i = from + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '$') {
                return i + 1;
            }
            boolean validTagChar = Character.isLetterOrDigit(c) || c == '_';
            if (!validTagChar) {
                return -1;
            }
            i++;
        }
        return -1;
    }

    private static void addStatement(@NotNull List<Statement> out, @NotNull String script,
                                     int start, int end, boolean started) {
        if (!started || start < 0 || end <= start) {
            return;
        }
        String raw = script.substring(start, end);
        if (raw.isBlank()) {
            return;
        }
        out.add(new Statement(raw, start, end));
    }
}
