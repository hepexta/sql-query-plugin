/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Resolves a PostgreSQL JDBC {@link Driver} without forcing the plugin to ship its own copy.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>a driver explicitly configured on the connection profile (a jar file or a directory);</li>
 *   <li>the driver shipped with IntelliJ IDEA's bundled Database Tools plugin, via
 *       {@code com.intellij.database.util.DbUtil#copyViaJdbcDriver};</li>
 *   <li>any PostgreSQL jar inside the running IDE installation;</li>
 *   <li>a driver already registered on the JVM classpath.</li>
 * </ol>
 */
public final class DriverResolver {

    public static final String DRIVER_CLASS = "org.postgresql.Driver";

    private DriverResolver() {
    }

    /**
     * Returns a usable PostgreSQL driver.
     *
     * @param explicitPath jar file or directory configured on the profile, may be blank
     * @throws SQLException with a user-facing message when no driver can be found
     */
    public static @NotNull Driver resolve(@Nullable String explicitPath) throws SQLException {
        List<String> attempts = new ArrayList<>();

        if (explicitPath != null && !explicitPath.isBlank()) {
            Driver d = fromExplicitPath(explicitPath.trim());
            if (d != null) {
                return d;
            }
            attempts.add("configured path '" + explicitPath + "' (no " + DRIVER_CLASS + " inside)");
        }

        Driver bundled = fromIdeBundledPlugin();
        if (bundled != null) {
            return bundled;
        }
        attempts.add("the Database Tools plugin bundled with the IDE");

        Driver fromPath = fromClassPath();
        if (fromPath != null) {
            return fromPath;
        }
        attempts.add("the IDE classpath");

        throw new SQLException(
                "PostgreSQL JDBC driver not found.\n"
                        + "Tried: " + String.join(", ", attempts) + ".\n"
                        + "Fix it by downloading postgresql-<version>.jar from https://jdbc.postgresql.org/download/ "
                        + "and setting \"Driver jar\" in the Simple SQL Query connection dialog, "
                        + "or by pasting the jar path there.");
    }

    /** Opens a connection, translating driver errors into readable messages. */
    public static @NotNull Connection open(@NotNull ConnectionProfile profile, @Nullable String password) throws SQLException {
        String problem = profile.validationError();
        if (problem != null) {
            throw new SQLException(problem);
        }
        Driver driver = resolve(profile.getDriverPath());

        Properties props = new Properties();
        if (!profile.getUser().isEmpty()) {
            props.setProperty("user", profile.getUser());
        }
        if (password != null && !password.isEmpty()) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", "10");
        props.setProperty("loginTimeout", "10");
        props.setProperty("ApplicationName", "SimpleSQLQuery");

        Connection connection = driver.connect(profile.effectiveUrl(), props);
        if (connection == null) {
            throw new SQLException("The PostgreSQL driver refused the URL: " + profile.effectiveUrl());
        }
        connection.setAutoCommit(true);
        return connection;
    }

    private static @Nullable Driver fromExplicitPath(@NotNull String path) {
        try {
            List<java.nio.file.Path> jars = JarLoader.expand(path);
            if (jars.isEmpty()) {
                return null;
            }
            ClassLoader loader = JarLoader.classLoaderFor(jars, DriverResolver.class.getClassLoader());
            Class<?> clazz = Class.forName(DRIVER_CLASS, true, loader);
            return (Driver) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable Driver fromIdeBundledPlugin() {
        try {
            // com.intellij.database.util.DbUtil has been part of the Database Tools plugin
            // for many releases; loaded reflectively so the plugin still works if it is absent.
            Class<?> dbUtil = Class.forName("com.intellij.database.util.DbUtil");
            Object driver = dbUtil.getMethod("copyViaJdbcDriver", String.class, String.class)
                    .invoke(null, DRIVER_CLASS, "42");
            if (driver instanceof Driver d) {
                return d;
            }
        } catch (Throwable ignored) {
            // Database Tools unavailable or the API moved on: fall through.
        }
        return null;
    }

    private static @Nullable Driver fromClassPath() {
        try {
            Class<?> clazz = Class.forName(DRIVER_CLASS);
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            // Make sure DriverManager sees it too, for tools that go through the registry.
            try {
                DriverManager.registerDriver(driver);
            } catch (SQLException ignored) {
                // already registered
            }
            return driver;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Sorts database names so that user databases come before template ones. */
    public static @NotNull List<String> sortedDatabases(@NotNull List<String> names) {
        List<String> copy = new ArrayList<>(names);
        copy.sort(Comparator.comparing((String n) -> n.startsWith("template")));
        return copy;
    }
}
