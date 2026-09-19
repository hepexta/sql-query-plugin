/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a user supplied path (a jar, a directory containing jars, or a classpath-like
 * list separated by {@code ;}) into a class loader.
 */
public final class JarLoader {

    private JarLoader() {
    }

    /** Expands the path into the list of jar files it refers to. */
    public static @NotNull List<Path> expand(@NotNull String path) {
        List<Path> result = new ArrayList<>();
        for (String part : path.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path p = Paths.get(trimmed);
            if (Files.isDirectory(p)) {
                result.addAll(listJars(p));
            } else if (Files.isRegularFile(p) && trimmed.toLowerCase().endsWith(".jar")) {
                result.add(p);
            }
        }
        return result;
    }

    public static @NotNull ClassLoader classLoaderFor(@NotNull List<Path> jars, @NotNull ClassLoader parent) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        return new URLClassLoader(urls, parent);
    }

    private static @NotNull List<Path> listJars(@NotNull Path dir) {
        try (Stream<Path> stream = Files.walk(dir, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
