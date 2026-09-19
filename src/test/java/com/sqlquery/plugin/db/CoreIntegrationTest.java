/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 hepexta
 *
 * Licensed under the MIT License. See the LICENSE file in the repository root.
 */
package com.sqlquery.plugin.db;

import org.junit.Assume;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs {@link CoreIntegrationCheck} against the PostgreSQL instance described by
 * {@code -Dpg.host} / {@code -Dpg.port} / {@code -Dpg.database} / {@code -Dpg.user} /
 * {@code -Dpg.password}, defaulting to the local {@code agents-infra/database} stack
 * (localhost:5432/allagents, agents/agents).
 *
 * <p>The test is skipped, not failed, when no server is listening, so the build stays green on
 * machines without the development database.</p>
 */
public class CoreIntegrationTest {

    @Test
    public void coreLayerWorksAgainstRealPostgres() {
        CoreIntegrationCheck.Settings settings = CoreIntegrationCheck.Settings.fromSystemProperties();
        boolean reachable = isReachable(settings.host(), settings.port());
        System.out.println("[CoreIntegrationTest] server " + settings.host() + ":" + settings.port()
                + " reachable=" + reachable);
        Assume.assumeTrue(
                "No PostgreSQL server on " + settings.host() + ":" + settings.port() + " - skipping integration test",
                reachable);

        CoreIntegrationCheck.Results results = new CoreIntegrationCheck.Results();
        int checks = CoreIntegrationCheck.run(settings, results::record);
        System.out.println("[CoreIntegrationTest] checks=" + checks
                + " passed=" + results.passed() + " failed=" + results.failures().size());

        List<String> failures = results.failures();
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + checks + " checks failed:\n  - "
                    + String.join("\n  - ", failures));
        }
        assertTrue("expected a meaningful number of checks to run, got " + checks, checks > 40);
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
