/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

/**
 * Recognises statements that deserve a confirmation prompt before they run.
 *
 * <p>Kept in the IntelliJ-free {@code db} layer so the headless suite can verify it, which
 * matters here: this is the only thing standing between an accidental keystroke and a
 * {@code DROP}. Two properties are load-bearing, and both are easy to lose:</p>
 *
 * <ul>
 *   <li>the script is <em>split first</em>, so a destructive statement hiding behind a harmless
 *       one ({@code select 1; drop table t;}) is still caught;</li>
 *   <li>leading comments are stripped with PostgreSQL's nesting rules, so
 *       {@code /* a /* b *}{@code / c *}{@code / drop table t} cannot smuggle one past the check.</li>
 * </ul>
 */
public final class StatementSafety {

    /**
     * Statements worth a confirmation prompt. Deliberately conservative: only keywords that
     * are essentially never accidental.
     */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "(?is)^(drop|truncate)\\b|\\bdelete\\s+from\\b|\\balter\\s+table\\s+\\S+\\s+drop\\b");

    private StatementSafety() {
    }

    /** True when any statement in {@code script} should be confirmed with the user before running. */
    public static boolean isDestructive(@NotNull String script) {
        for (SqlSplitter.Statement statement : SqlSplitter.split(script)) {
            if (DESTRUCTIVE.matcher(stripLeadingComments(statement.sql())).find()) {
                return true;
            }
        }
        return false;
    }

    /** Removes leading comments and whitespace, honouring PostgreSQL's nested block comments. */
    private static @NotNull String stripLeadingComments(@NotNull String sql) {
        String s = sql.stripLeading();
        while (true) {
            if (s.startsWith("--")) {
                int newline = s.indexOf('\n');
                if (newline < 0) {
                    return "";
                }
                s = s.substring(newline + 1).stripLeading();
            } else if (s.startsWith("/*")) {
                int end = endOfBlockComment(s);
                if (end < 0) {
                    return "";
                }
                s = s.substring(end).stripLeading();
            } else {
                return s;
            }
        }
    }

    /**
     * Index just past the block comment starting at offset 0, or {@code -1} when it is never
     * closed.
     *
     * <p>Nesting is counted the way PostgreSQL counts it. Taking the first {@code *}{@code /}
     * instead would end the comment early, leave {@code *}{@code / drop table t} as the apparent
     * statement, and hide the {@code DROP} from {@link #isDestructive}.</p>
     */
    private static int endOfBlockComment(@NotNull String s) {
        int depth = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            char c = s.charAt(i);
            char next = s.charAt(i + 1);
            if (c == '/' && next == '*') {
                depth++;
                i++;
            } else if (c == '*' && next == '/') {
                depth--;
                i++;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
