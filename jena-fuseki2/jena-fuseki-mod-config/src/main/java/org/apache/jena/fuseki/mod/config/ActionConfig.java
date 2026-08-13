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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonBuilder;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.fuseki.ctl.ActionCtl;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.fuseki.servlets.ServletOps;
import org.apache.jena.http.HttpMethod;
import org.apache.jena.riot.WebContent;

/**
 * Read-only browsing of the configuration a server is running.
 *
 * <pre>
 * GET /$/config                     the server configuration file        (Turtle)
 * GET /$/config/effective           what the server actually resolved    (JSON)
 * GET /$/config/datasets            dataset configuration files          (JSON)
 * GET /$/config/datasets/{name}     one dataset's configuration file     (Turtle)
 * </pre>
 *
 * <h3>Why the paths are shaped this way</h3>
 *
 * Fuseki calls two different files "config", and an API that hides that behind one path
 * inherits the confusion. The {@code --config} file is the server configuration: there is
 * at most one, and it is the only place a server-wide setting such as a timeout can live.
 * The files in {@code FUSEKI_BASE/configuration/} are dataset configurations: one per
 * dataset, each parsed into its own graph, never merged, and unable to carry a
 * {@code fuseki:Server} at all.
 * <p>
 * So the root is the server configuration — singular, because Fuseki allows only one —
 * and dataset configurations live under their own collection, keyed by dataset name.
 * {@code FusekiServerCtl.generateConfigurationFilename} writes {@code <dsName>.ttl}, so
 * the name is the real key and no opaque identifier is needed.
 * <p>
 * Each path returns one thing. An earlier revision switched the root between Turtle and
 * JSON on the {@code Accept} header, which made the response type depend on something a
 * reader of the URL cannot see, and turned a browser's {@code *&#47;*} into a question
 * about content negotiation. A path per resource needs no such rule.
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
 * Files are served whole. Behind the admin gate, reading configuration grants nothing the
 * caller does not already have — {@code /$/datasets} POST already writes config files —
 * there is no reliable rule for what counts as sensitive in an arbitrary assembler graph,
 * and a silently holed file defeats the purpose of a browse view. Treat this endpoint as
 * exactly as sensitive as the files.
 *
 * <h3>Authentication</h3>
 *
 * This registers under {@code /$/}, which the bundled default {@code shiro.ini} restricts
 * with {@code /$/** = localhostFilter} — network position, not authentication. Note that a
 * reverse proxy in front of Fuseki defeats that filter entirely, because the filter
 * compares the socket's remote address and the proxy connects from localhost. Anything
 * fronting Fuseki must restrict admin paths itself, or Shiro must require real
 * credentials.
 */
public class ActionConfig extends ActionCtl {

    /** Sub-collection holding the per-dataset configuration files. */
    private static final String DATASETS = "datasets";

    /** The resolved view of the running server. */
    private static final String EFFECTIVE = "effective";

    /**
     * The configuration as captured when the server started. Held by reference because
     * the servlet is built during {@code prepare} but the capture happens later, once
     * every module has set up its part of the run area.
     */
    private final AtomicReference<ConfigSources.Captured> captured;

    public ActionConfig(AtomicReference<ConfigSources.Captured> captured) {
        super();
        this.captured = captured;
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
        ConfigSources.Captured config = captured.get();
        if ( config == null )
            config = ConfigSources.Captured.EMPTY;

        String[] segments = pathSegments(action);
        try {
            switch ( segments.length ) {
                case 0 -> serveServerConfig(action, config);
                case 1 -> {
                    if ( EFFECTIVE.equals(segments[0]) )
                        EffectiveConfig.write(action, config);
                    else if ( DATASETS.equals(segments[0]) )
                        listDatasets(action, config);
                    else
                        ServletOps.errorNotFound("No such configuration resource: " + segments[0]);
                }
                case 2 -> {
                    if ( DATASETS.equals(segments[0]) )
                        serveDataset(action, config, segments[1]);
                    else
                        ServletOps.errorNotFound("No such configuration resource: " + segments[0]);
                }
                default -> ServletOps.errorNotFound("No such configuration resource");
            }
        } catch (IOException e) {
            ServletOps.errorOccurred(e);
        }
    }

    /** Path below {@code /$/config}, split and emptied of blanks. */
    private static String[] pathSegments(HttpAction action) {
        String trailing = action.getRequestPathInfo();
        if ( trailing == null || trailing.isEmpty() || "/".equals(trailing) )
            return new String[0];
        return Arrays.stream(trailing.split("/"))
                     .filter(seg -> !seg.isEmpty())
                     .toArray(String[]::new);
    }

    /** The single {@code --config} file. */
    private void serveServerConfig(HttpAction action, ConfigSources.Captured config) throws IOException {
        ConfigSources.Source server = config.server();
        if ( server == null ) {
            ServletOps.errorNotFound("This server has no server configuration file"
                                     + " (started from the command line or programmatically)."
                                     + " Dataset configurations, if any, are under /$/config/datasets");
            return;
        }
        serveFile(action, server);
    }

    private void listDatasets(HttpAction action, ConfigSources.Captured config) throws IOException {
        JsonBuilder builder = new JsonBuilder();
        builder.startObject();
        builder.key(DATASETS).startArray();
        for ( ConfigSources.Source s : config.datasets().values() )
            describe(builder, s);
        builder.finishArray();
        builder.finishObject();
        writeJson(action, builder.build());
    }

    private void serveDataset(HttpAction action, ConfigSources.Captured config, String name) throws IOException {
        // Fuseki writes dataset names with a leading "/" in some contexts and without in
        // filenames; accept either so a caller can paste a name straight from /$/datasets.
        String key = name.startsWith("/") ? name.substring(1) : name;
        ConfigSources.Source source = config.datasets().get(key);
        if ( source == null ) {
            ServletOps.errorNotFound("No configuration file for dataset: " + name);
            return;
        }
        serveFile(action, source);
    }

    /** Describe a source without its content; the content has its own URL. */
    static void describe(JsonBuilder builder, ConfigSources.Source s) {
        builder.startObject();
        if ( s.name() != null )
            builder.pair("name", s.name());
        builder.pair("path", s.path())
               .pair("readable", s.readable())
               // The server is still running what it read at startup. If the file has
               // been edited since, say so rather than letting a reader assume the
               // difference does not exist.
               .pair("changedOnDisk", s.changedOnDisk())
               .pair("onDiskReadable", s.onDiskReadable());
        builder.finishObject();
    }

    private void serveFile(HttpAction action, ConfigSources.Source source) throws IOException {
        if ( !source.readable() ) {
            ServletOps.errorOccurred("Configuration file was not readable at startup: " + source.path());
            return;
        }
        // The captured bytes, not the current file: this is what the server is running.
        String text = source.text();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        action.setResponseContentType(source.contentType());
        action.setResponseCharacterEncoding(WebContent.charsetUTF8);
        action.setResponseHeader("Content-Disposition",
            "inline; filename=\"" + Path.of(source.path()).getFileName() + "\"");
        if ( source.changedOnDisk() ) {
            // A header rather than an error: the caller asked for the running
            // configuration and is getting exactly that. This tells them the file has
            // moved on, which is the thing they could not otherwise find out.
            action.setResponseHeader("Warning",
                "199 - \"Config file on disk has changed since startup; serving what the server loaded\"");
        }
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
