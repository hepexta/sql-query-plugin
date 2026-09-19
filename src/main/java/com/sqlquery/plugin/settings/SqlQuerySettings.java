/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.sqlquery.plugin.db.ConnectionProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted plugin configuration: the list of connection profiles, which one is active,
 * and the safety switches for execution.
 *
 * <p>Passwords live in {@link PasswordSafe}, never in the profile objects, so the
 * component state written to disk contains no secrets.</p>
 */
@Service(Service.Level.APP)
@State(name = "SimpleSqlQuerySettings", storages = @Storage("simple-sql-query.xml"))
public final class SqlQuerySettings implements PersistentStateComponent<SqlQuerySettings.State> {

    /** Credential store key suffix, namespaced so we never collide with other plugins. */
    private static final String CREDENTIAL_KEY_PREFIX = "SimpleSqlQuery/";

    private State state = new State();

    public static SqlQuerySettings getInstance() {
        return ApplicationManager.getApplication().getService(SqlQuerySettings.class);
    }

    /** Serializable form of the configuration. Public fields are required by the XML serializer. */
    public static final class State {
        public List<ConnectionProfile> profiles = new ArrayList<>();
        public String activeProfileName = "";
        public boolean autoCommit = true;
        public boolean confirmDestructiveStatements = false;
    }

    @Override
    public @NotNull State getState() {
        seedIfEmpty();
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
        seedIfEmpty();
    }

    /**
     * Makes sure the configuration is usable.
     *
     * <p>This cannot live in {@link #loadState} alone: the platform only calls it when a
     * configuration file already exists, so on a first run the defaults have to be created
     * lazily instead.</p>
     */
    private void seedIfEmpty() {
        if (state.profiles == null) {
            state.profiles = new ArrayList<>();
        }
        if (state.profiles.isEmpty()) {
            state.profiles.add(defaultProfile());
        }
        if (state.activeProfileName == null || state.activeProfileName.isBlank()) {
            state.activeProfileName = state.profiles.get(0).getName();
        }
    }

    /** Seeds a profile matching the common local PostgreSQL defaults on first run. */
    private static @NotNull ConnectionProfile defaultProfile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setName("Local PostgreSQL");
        profile.setHost("localhost");
        profile.setPort(ConnectionProfile.DEFAULT_PORT);
        profile.setDatabase("postgres");
        profile.setUser("postgres");
        profile.setSslMode("prefer");
        return profile;
    }

    // ------------------------------------------------------------------ profiles

    public @NotNull List<ConnectionProfile> getProfiles() {
        seedIfEmpty();
        return state.profiles;
    }

    public @Nullable ConnectionProfile findProfile(@Nullable String name) {
        if (name == null) {
            return null;
        }
        for (ConnectionProfile profile : getProfiles()) {
            if (name.equals(profile.getName())) {
                return profile;
            }
        }
        return null;
    }

    public @Nullable ConnectionProfile getActiveProfile() {
        seedIfEmpty();
        ConnectionProfile profile = findProfile(state.activeProfileName);
        if (profile == null && !state.profiles.isEmpty()) {
            profile = state.profiles.get(0);
            state.activeProfileName = profile.getName();
        }
        return profile;
    }

    public void setActiveProfile(@NotNull String name) {
        state.activeProfileName = name;
    }

    public @NotNull String getActiveProfileName() {
        ConnectionProfile active = getActiveProfile();
        return active == null ? "" : active.getName();
    }

    /**
     * Replaces the whole profile list. Because the password key is derived from the profile
     * name, any profile that disappeared has its stored password removed.
     */
    public void replaceProfiles(@NotNull List<ConnectionProfile> profiles) {
        List<String> removed = new ArrayList<>();
        for (ConnectionProfile existing : state.profiles) {
            boolean stillPresent = profiles.stream()
                    .anyMatch(p -> p.getName().equals(existing.getName()));
            if (!stillPresent) {
                removed.add(existing.getName());
            }
        }
        state.profiles = new ArrayList<>(profiles);
        for (String name : removed) {
            setPassword(name, null);
        }
        if (findProfile(state.activeProfileName) == null) {
            state.activeProfileName = profiles.isEmpty() ? "" : profiles.get(0).getName();
        }
    }

    // ----------------------------------------------------------------- execution

    public boolean isAutoCommit() {
        return state.autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        state.autoCommit = autoCommit;
    }

    public boolean isConfirmDestructiveStatements() {
        return state.confirmDestructiveStatements;
    }

    public void setConfirmDestructiveStatements(boolean confirm) {
        state.confirmDestructiveStatements = confirm;
    }

    // ------------------------------------------------------------------ passwords

    public @Nullable String getPassword(@Nullable String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        Credentials credentials = PasswordSafe.getInstance().get(credentialAttributes(profileName));
        return credentials == null ? null : credentials.getPasswordAsString();
    }

    public void setPassword(@Nullable String profileName, @Nullable String password) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        CredentialAttributes attributes = credentialAttributes(profileName);
        if (password == null || password.isEmpty()) {
            PasswordSafe.getInstance().set(attributes, null);
        } else {
            PasswordSafe.getInstance().set(attributes, new Credentials(profileName, password));
        }
    }

    private static @NotNull CredentialAttributes credentialAttributes(@NotNull String profileName) {
        return new CredentialAttributes(CREDENTIAL_KEY_PREFIX + profileName);
    }
}
