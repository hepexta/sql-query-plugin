/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A saved PostgreSQL connection definition.
 *
 * <p>Passwords are deliberately <em>not</em> part of this object: they are kept in the
 * IDE credential store (KeePass by default) and looked up on demand, so they never end
 * up in {@code .idea/} XML on disk.</p>
 */
public final class ConnectionProfile {

    public static final int DEFAULT_PORT = 5432;

    private String name = "Local PostgreSQL";
    private String host = "localhost";
    private int port = DEFAULT_PORT;
    private String database = "postgres";
    private String user = "postgres";
    private String sslMode = "prefer";
    private String urlOverride = "";
    private String driverPath = "";
    private boolean storePassword = true;
    private int statementTimeoutSeconds = 30;
    private int fetchLimit = 500;

    public ConnectionProfile() {
    }

    public static @NotNull ConnectionProfile copyOf(@NotNull ConnectionProfile other) {
        ConnectionProfile p = new ConnectionProfile();
        p.name = other.name;
        p.host = other.host;
        p.port = other.port;
        p.database = other.database;
        p.user = other.user;
        p.sslMode = other.sslMode;
        p.urlOverride = other.urlOverride;
        p.driverPath = other.driverPath;
        p.storePassword = other.storePassword;
        p.statementTimeoutSeconds = other.statementTimeoutSeconds;
        p.fetchLimit = other.fetchLimit;
        return p;
    }

    public @NotNull String getName() {
        return name == null || name.isBlank() ? "unnamed" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public @NotNull String getHost() {
        return host == null || host.isBlank() ? "localhost" : host.trim();
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port <= 0 || port > 65535 ? DEFAULT_PORT : port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public @NotNull String getDatabase() {
        return database == null ? "" : database.trim();
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public @NotNull String getUser() {
        return user == null ? "" : user.trim();
    }

    public void setUser(String user) {
        this.user = user;
    }

    public @NotNull String getSslMode() {
        return sslMode == null || sslMode.isBlank() ? "prefer" : sslMode.trim();
    }

    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }

    public @NotNull String getUrlOverride() {
        return urlOverride == null ? "" : urlOverride.trim();
    }

    public void setUrlOverride(String urlOverride) {
        this.urlOverride = urlOverride;
    }

    public @NotNull String getDriverPath() {
        return driverPath == null ? "" : driverPath.trim();
    }

    public void setDriverPath(String driverPath) {
        this.driverPath = driverPath;
    }

    public boolean isStorePassword() {
        return storePassword;
    }

    public void setStorePassword(boolean storePassword) {
        this.storePassword = storePassword;
    }

    public int getStatementTimeoutSeconds() {
        return statementTimeoutSeconds <= 0 ? 30 : statementTimeoutSeconds;
    }

    public void setStatementTimeoutSeconds(int statementTimeoutSeconds) {
        this.statementTimeoutSeconds = statementTimeoutSeconds;
    }

    public int getFetchLimit() {
        return fetchLimit <= 0 ? 500 : fetchLimit;
    }

    public void setFetchLimit(int fetchLimit) {
        this.fetchLimit = fetchLimit;
    }

    /** JDBC URL, either the explicit override or one built from the individual fields. */
    public @NotNull String effectiveUrl() {
        if (!getUrlOverride().isEmpty()) {
            return getUrlOverride();
        }
        return "jdbc:postgresql://" + getHost() + ":" + getPort() + "/" + getDatabase()
                + "?sslmode=" + getSslMode()
                + "&ApplicationName=SimpleSQLQuery";
    }

    /** A short human readable target, used in the status bar. */
    public @NotNull String describeTarget() {
        if (!getUrlOverride().isEmpty()) {
            return getUrlOverride();
        }
        return getUser() + "@" + getHost() + ":" + getPort() + "/" + getDatabase();
    }

    /** Explains why the profile cannot be used yet, or {@code null} when it is complete. */
    public @Nullable String validationError() {
        if (getUrlOverride().isEmpty()) {
            if (getDatabase().isEmpty()) {
                return "Database is required.";
            }
            if (getUser().isEmpty()) {
                return "User is required.";
            }
        } else if (!getUrlOverride().startsWith("jdbc:postgresql:")) {
            return "The JDBC URL override must start with jdbc:postgresql:";
        }
        return null;
    }

    @Override
    public String toString() {
        return getName();
    }
}
