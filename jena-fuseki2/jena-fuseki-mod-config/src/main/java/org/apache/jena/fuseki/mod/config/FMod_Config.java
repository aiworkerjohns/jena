/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.apache.jena.fuseki.mod.config;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.cmd.CmdGeneral;
import org.apache.jena.fuseki.main.runner.ServerArgs;
import org.apache.jena.fuseki.main.sys.FusekiAutoModule;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.apache.jena.rdf.model.Model;

/**
 * Adds {@code /$/config}, a read-only view of the configuration the server is running.
 * <p>
 * A separate Maven module rather than files added to {@code jena-fuseki-main}: this fork
 * has added no Fuseki-side code so far, and keeping it that way keeps the monthly upstream
 * merge to its existing conflict set. The pattern is
 * {@code jena-fuseki-mod-geosparql}'s — a {@code FusekiAutoModule} discovered through
 * {@code META-INF/services}.
 *
 * @see ActionConfig for the endpoint's shape, and for why it is read-only and unredacted
 */
public class FMod_Config implements FusekiAutoModule {

    public static FMod_Config create() {
        return new FMod_Config();
    }

    public FMod_Config() {}

    /**
     * The {@code --config} file, captured from command line processing.
     * <p>
     * {@link FusekiServer#getConfigFilename()} cannot be used for a command-line server:
     * {@code FusekiArgs} reads the file itself and calls {@code builder.parseConfig(Model)},
     * and only {@code parseConfigFile(String)} records the name. So a server started with
     * {@code --config} reports a null config filename. (The same gap is why
     * {@code ActionReload} could not work for one, were it registered.) This hook runs
     * before {@code prepare}, so the name is in hand by the time the servlet is built.
     */
    private String cmdlineConfigFile = null;

    /**
     * Configuration as captured at startup, shared with the servlets.
     * <p>
     * Populated in {@link #configured}, not {@link #prepare}: {@code FMod_Admin} sets up
     * {@code FusekiServerCtl.dirConfiguration} during its own {@code prepare}, and module
     * order is not something to rely on. {@code configured} runs after every module's
     * {@code prepare} and still before the server accepts requests.
     */
    private final AtomicReference<ConfigSources.Captured> captured =
        new AtomicReference<>(ConfigSources.Captured.EMPTY);

    @Override
    public String name() {
        return "Configuration";
    }

    @Override
    public void serverArgsPrepare(CmdGeneral fusekiCmd, ServerArgs serverArgs) {
        cmdlineConfigFile = serverArgs.serverConfigFile;
    }

    @Override
    public void prepare(FusekiServer.Builder builder, Set<String> datasetNames, Model configModel) {
        // Both paths are registered: "/$/config" for the listing, "/$/config/*" for an
        // individual source. A single "/*" pattern would not match the bare path.
        builder.addServlet("/$/config", new ActionConfig(captured));
        builder.addServlet("/$/config/*", new ActionConfig(captured));
    }

    @Override
    public void configured(FusekiServer.Builder builder, DataAccessPointRegistry dapRegistry, Model configModel) {
        // Covers the command-line case, and does so before the server accepts anything.
        if ( cmdlineConfigFile != null )
            captured.set(ConfigSources.capture(cmdlineConfigFile));
    }

    @Override
    public void serverAfterStarting(FusekiServer server) {
        // A programmatically built server records its config file on the server object
        // rather than in the command line arguments, and that is only reachable here.
        if ( cmdlineConfigFile == null )
            captured.set(ConfigSources.capture(server.getConfigFilename()));
    }
}
