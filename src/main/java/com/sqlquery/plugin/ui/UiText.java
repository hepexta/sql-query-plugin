/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns text that came from outside the plugin — database values, column labels, server error
 * messages — into something safe to hand to a Swing text component.
 *
 * <p>{@code JLabel}, {@code JBLabel}, {@code JToolTip} and the {@code Messages} dialogs all treat
 * a string starting with {@code <html>} as markup rather than as text. That is fine for strings
 * this plugin authors, and wrong for strings a database server supplies: a value of
 * {@code <html><img src="http://example.invalid/x.png">} renders as a live HTML document, which
 * both misrepresents the data and lets it reach out to whatever host it names.</p>
 *
 * <p>{@link #asHtml} escapes the text and wraps it in a document, so the characters are displayed
 * exactly as stored, and no markup in them is interpreted.</p>
 */
final class UiText {

    private UiText() {
    }

    /** Returns {@code text} as an HTML document that displays it literally, never as markup. */
    static @NotNull String asHtml(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        sb.append("<html>");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\n' -> sb.append("<br>");
                case '\r' -> {
                    // folded into the \n above, or dropped when it stands alone
                }
                default -> sb.append(c);
            }
        }
        return sb.append("</html>").toString();
    }
}
