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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonBuilder;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.fuseki.ctl.ActionCtl;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.fuseki.servlets.ServletOps;
import org.apache.jena.http.HttpMethod;
import org.apache.jena.riot.WebContent;

/**
 * Read-only browsing of the configuration a server is running.
 *
 * <pre>
 * GET /$/config                    list of configuration sources (JSON)
 * GET /$/config/{id}               the file's bytes (text/turtle)
 * GET /$/config/{id}?view=effective  what the server actually resolved (JSON)
 * </pre>
 *
 * <h3>Read-only, and why that is the whole design</h3>
 *
 * There is no write path. Writing configuration would be remote configuration of class
 * loading — {@code FusekiConfig.processLoadClass} loads arbitrary classes named in a
 * config file — and there is nothing to apply an edit with in any case: {@code
 * ActionReload} is registered only in a test, so a running server has no {@code
 * /$/reload}. A GUI edit is done client-side against the bytes this endpoint serves and
 * downloaded as a file, which also preserves the comments and prefixes that
 * re-serialising a parsed model would destroy.
 *
 * <h3>No redaction</h3>
 *
 * The file is served whole. Behind the admin gate, reading configuration grants nothing
 * the caller does not already have — {@code /$/datasets} POST already writes config files
 * — there is no reliable rule for what counts as sensitive in an arbitrary assembler
 * graph, and a silently holed file defeats the purpose of a browse view. Treat this
 * endpoint as exactly as sensitive as the file.
 *
 * <h3>Authentication</h3>
 *
 * This registers under {@code /$/}, which the bundled default {@code shiro.ini} restricts
 * with {@code /$/** = localhostFilter} — network position, not authentication. A
 * deployment that wants this reachable must enable real auth
 * ({@code /$/** = authcBasic,user[admin]}) rather than adding an {@code anon} rule.
 */
public class ActionConfig extends ActionCtl {

    /** The {@code --config} file, when the command line supplied one. May be null. */
    private final String cmdlineConfigFile;

    public ActionConfig() { this(null); }

    public ActionConfig(String cmdlineConfigFile) {
        super();
        this.cmdlineConfigFile = cmdlineConfigFile;
    }

    @Override
    public void validate(HttpAction action) {
        if ( !HttpMethod.METHOD_GET.equals(action.getRequestMethod()) )
            ServletOps.errorMethodNotAllowed(action.getRequestMethod() + " : read-only endpoint");
    }

    @Override
    public void execGet(HttpAction action) {
        executeLifecycle(action);
    }

    @Override
    public void execute(HttpAction action) {
        // A command-line server reports no config filename (see FMod_Config), so prefer
        // the name captured during argument processing and fall back to the builder's,
        // which is set for a programmatically configured server.
        FusekiServer server = FusekiServer.get(action.getRequest().getServletContext());
        String serverConfig = cmdlineConfigFile;
        if ( serverConfig == null && server != null )
            serverConfig = server.getConfigFilename();

        String id = itemId(action);
        try {
            if ( id == null ) {
                listSources(action, serverConfig);
                return;
            }
            ConfigSources.Source source = ConfigSources.find(serverConfig, id);
            if ( source == null ) {
                ServletOps.errorNotFound("No such configuration source");
                return;
            }
            if ( "effective".equals(action.getRequestParameter("view")) )
                EffectiveConfig.write(action, source);
            else
                serveRaw(action, source);
        } catch (IOException e) {
            ServletOps.errorOccurred(e);
        }
    }

    /** The path segment after {@code /$/config}, or null for the container itself. */
    private static String itemId(HttpAction action) {
        String trailing = action.getRequestPathInfo();
        if ( trailing == null || trailing.isEmpty() || "/".equals(trailing) )
            return null;
        String id = trailing.startsWith("/") ? trailing.substring(1) : trailing;
        return id.isEmpty() ? null : id;
    }

    private void listSources(HttpAction action, String serverConfig) throws IOException {
        JsonBuilder builder = new JsonBuilder();
        builder.startObject();
        builder.key("sources").startArray();
        for ( ConfigSources.Source s : ConfigSources.list(serverConfig) ) {
            builder.startObject()
                   .pair("id", s.id())
                   .pair("kind", s.kind())
                   .pair("path", s.path())
                   .pair("readable", s.readable())
                   .finishObject();
        }
        builder.finishArray();
        builder.finishObject();
        writeJson(action, builder.build());
    }

    private void serveRaw(HttpAction action, ConfigSources.Source source) throws IOException {
        if ( !source.readable() ) {
            ServletOps.errorOccurred("Configuration file is not readable: " + source.path());
            return;
        }
        String text = ConfigSources.read(source);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        action.setResponseContentType(WebContent.contentTypeTurtle);
        action.setResponseCharacterEncoding(WebContent.charsetUTF8);
        // Lets a browsing client skip the transfer when nothing has changed. Content-based
        // rather than mtime-based, so touching a file without editing it is not a change.
        action.setResponseHeader("ETag", "\"" + Integer.toHexString(text.hashCode()) + "\"");
        OutputStream out = action.getResponseOutputStream();
        out.write(bytes);
        out.flush();
        ServletOps.success(action);
    }

    static void writeJson(HttpAction action, JsonValue value) throws IOException {
        action.setResponseContentType(WebContent.contentTypeJSON);
        action.setResponseCharacterEncoding(WebContent.charsetUTF8);
        OutputStream out = action.getResponseOutputStream();
        JSON.write(out, value);
        out.write('\n');
        out.flush();
        ServletOps.success(action);
    }
}
