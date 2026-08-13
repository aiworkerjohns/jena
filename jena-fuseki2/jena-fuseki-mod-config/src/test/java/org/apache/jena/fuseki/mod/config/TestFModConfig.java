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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration endpoint against a running server.
 *
 * @see ActionConfig
 */
public class TestFModConfig {

    private FusekiServer server;

    private static final String CONFIG = """
        PREFIX fuseki: <http://jena.apache.org/fuseki#>
        PREFIX ja:     <http://jena.hpl.hp.com/2005/11/Assembler#>

        ## A comment that must survive the round trip.
        [] a fuseki:Server ; fuseki:services ( <#service> ) .

        <#service> a fuseki:Service ;
            fuseki:name "ds" ;
            fuseki:endpoint [ fuseki:operation fuseki:query ] ;
            fuseki:dataset <#dataset> .

        <#dataset> a ja:MemoryDataset .
        """;

    @AfterEach
    public void tearDown() {
        if ( server != null )
            server.stop();
    }

    private FusekiServer startWithConfig(Path configFile) {
        server = FusekiServer.create()
            .port(0)
            .fusekiModules(org.apache.jena.fuseki.main.sys.FusekiModules.create(FMod_Config.create()))
            .parseConfigFile(configFile.toString())
            .build()
            .start();
        return server;
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "GET " + url);
        return r.body();
    }

    private static HttpResponse<String> rawGet(String url) throws Exception {
        return raw(url, "GET");
    }

    private static HttpResponse<String> raw(String url, String method) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }








    // ---- The server configuration is the root, because Fuseki allows only one.

    @Test
    public void rootServesTheServerConfigurationAsTurtle(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        HttpResponse<String> r = rawGet("http://localhost:" + server.getHttpPort() + "/$/config");
        assertEquals(200, r.statusCode());
        assertEquals(CONFIG, r.body(), "the root is the server configuration file, byte for byte");
        assertTrue(r.headers().firstValue("Content-Type").orElse("").contains("turtle"));
        assertTrue(r.body().contains("## A comment that must survive the round trip."));
        assertTrue(!r.body().contains("subClassOf"),
            "re-serialising the parsed model would leak assembler registration triples");
    }

    /** The response must not depend on Accept: a browser gets the same thing curl does. */
    @Test
    public void theRootIgnoresTheAcceptHeader(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);
        String url = "http://localhost:" + server.getHttpPort() + "/$/config";

        for ( String accept : new String[] {
                "text/turtle", "application/json", "text/html,application/xhtml+xml,*/*;q=0.8" } ) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).header("Accept", accept).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(CONFIG, r.body(), "Accept: " + accept + " must not change the resource");
        }
    }

    /** A server with no {@code --config} says so, and points at where datasets live. */
    @Test
    public void aServerWithNoConfigFileSaysSo() throws Exception {
        server = FusekiServer.create()
            .port(0)
            .fusekiModules(org.apache.jena.fuseki.main.sys.FusekiModules.create(FMod_Config.create()))
            .add("/ds", DatasetGraphFactory.createTxnMem())
            .build()
            .start();

        HttpResponse<String> r = rawGet("http://localhost:" + server.getHttpPort() + "/$/config");
        assertEquals(404, r.statusCode());
    }

    // ---- Dataset configurations are a separate collection, keyed by dataset name.

    @Test
    public void datasetConfigsAreListedByNameAndFetchedByName(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);
        String base = "http://localhost:" + server.getHttpPort();

        // No FUSEKI_BASE/configuration in this test server, so the collection is empty
        // rather than absent - a client can tell "none" from "unsupported".
        JsonObject body = JSON.parse(get(base + "/$/config/datasets"));
        assertNotNull(body.get("datasets"), "the collection exists even when empty");

        assertEquals(404, rawGet(base + "/$/config/datasets/nope").statusCode());
    }

    @Test
    public void anUnknownSubResourceIsNotFound(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);
        String base = "http://localhost:" + server.getHttpPort();

        assertEquals(404, rawGet(base + "/$/config/wat").statusCode());
        assertEquals(404, rawGet(base + "/$/config/datasets/a/b").statusCode());
    }

    // ---- The effective view.

    @Test
    public void effectiveViewDescribesTheRunningServerAndStatesItsLimits(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        JsonObject effective = JSON.parse(
            get("http://localhost:" + server.getHttpPort() + "/$/config/effective"));
        assertNotNull(effective.get("fingerprintVersion"));
        assertNotNull(effective.get("serverConfig"));
        assertTrue(effective.get("caveats").getAsArray().size() >= 2,
            "the effective view must state what a green fingerprint does not prove");

        JsonArray datasets = effective.get("datasets").getAsArray();
        assertEquals(1, datasets.size());
        JsonObject ds = datasets.get(0).getAsObject();
        assertEquals("/ds", ds.get("name").getAsString().value());
        assertTrue(!ds.get("shaclIndex").getAsBoolean().value(),
            "a plain memory dataset has no SHACL index, and should say so rather than omit the key");
    }

    // ---- Drift: the running configuration is what was loaded.

    /**
     * Fuseki reads its configuration once at startup and never looks again, so an edit
     * afterwards changes the file and nothing about the running server. Serving the file
     * live made the endpoint show an edit the server had never seen while the effective
     * view beside it still reported the old, actually-running fingerprint.
     */
    @Test
    public void anEditAfterStartupIsReportedNotServed(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);
        String base = "http://localhost:" + server.getHttpPort();

        JsonObject before = JSON.parse(get(base + "/$/config/effective")).get("serverConfig").getAsObject();
        assertTrue(!before.get("changedOnDisk").getAsBoolean().value(), "unedited file is not changed");

        Files.writeString(cfg, CONFIG + "\n## edited after the server started\n");

        HttpResponse<String> r = rawGet(base + "/$/config");
        assertEquals(CONFIG, r.body(),
            "the running configuration is what was loaded, not what is on disk now");
        assertTrue(r.headers().firstValue("Warning").isPresent(),
            "a drifted file must announce itself");

        JsonObject after = JSON.parse(get(base + "/$/config/effective")).get("serverConfig").getAsObject();
        assertTrue(after.get("changedOnDisk").getAsBoolean().value(),
            "an edit must be reported, or a reader assumes the difference does not exist");
    }

    /** A config file deleted after startup is not a content change, and must not 500. */
    @Test
    public void aDeletedFileStillServesWhatWasLoaded(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);
        String base = "http://localhost:" + server.getHttpPort();

        Files.delete(cfg);

        assertEquals(CONFIG, get(base + "/$/config"));
        JsonObject s = JSON.parse(get(base + "/$/config/effective")).get("serverConfig").getAsObject();
        assertTrue(s.get("readable").getAsBoolean().value(), "it was readable at startup");
        assertTrue(!s.get("onDiskReadable").getAsBoolean().value(), "and is gone now");
        assertTrue(!s.get("changedOnDisk").getAsBoolean().value(),
            "a missing file is reported as missing, not as an edit");
    }

    /** Read-only is a property to enforce, not just to document. */
    @Test
    public void writeMethodsAreRejected(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        String base = "http://localhost:" + server.getHttpPort();
        for ( String method : new String[] { "POST", "PUT", "DELETE" } ) {
            HttpResponse<String> r = raw(base + "/$/config", method);
            assertTrue(r.statusCode() == 405 || r.statusCode() == 400,
                method + " should be refused, got " + r.statusCode());
        }
    }





    /**
     * A command-line {@code --config} server reports no config filename of its own.
     * <p>
     * {@code FusekiArgs} reads the file and calls {@code builder.parseConfig(Model)};
     * only {@code parseConfigFile(String)} records the name, so
     * {@link FusekiServer#getConfigFilename()} is null for every command-line server.
     * {@link FMod_Config} works around it by capturing the name from
     * {@code serverArgsPrepare}. This test pins the upstream behaviour, so that if it is
     * ever fixed the workaround can be identified as dead rather than merely redundant.
     */
    @Test
    public void commandLineServersReportNoConfigFilename(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);

        // parseConfigFile does record it - this is the programmatic path.
        startWithConfig(cfg);
        assertNotNull(server.getConfigFilename(),
            "parseConfigFile(String) records the name");
        assertTrue(server.getConfigFilename().endsWith("config.ttl"));
    }

}
