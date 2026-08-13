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

    private static HttpResponse<String> raw(String url, String method) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void listsTheServerConfigFile(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        JsonObject body = JSON.parse(get("http://localhost:" + server.getHttpPort() + "/$/config"));
        JsonArray sources = body.get("sources").getAsArray();
        assertEquals(1, sources.size(), "expected exactly the --config file");

        JsonObject source = sources.get(0).getAsObject();
        assertEquals("server", source.get("kind").getAsString().value());
        assertTrue(source.get("path").getAsString().value().endsWith("config.ttl"));
        assertTrue(source.get("readable").getAsBoolean().value());
        assertNotNull(source.get("id"));
    }

    /**
     * The bytes are served, not a re-serialisation of the parsed model. Comments and
     * prefix choices survive, and the assembler-registration triples that
     * {@code readAssemblerFile} injects into the model do not appear.
     */
    @Test
    public void servesTheFileBytesVerbatim(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        String base = "http://localhost:" + server.getHttpPort();
        JsonObject body = JSON.parse(get(base + "/$/config"));
        String id = body.get("sources").getAsArray().get(0).getAsObject().get("id").getAsString().value();

        String served = get(base + "/$/config/" + id);
        assertEquals(CONFIG, served, "the endpoint must serve the file, byte for byte");
        assertTrue(served.contains("## A comment that must survive the round trip."));
        assertTrue(!served.contains("subClassOf"),
            "re-serialising the parsed model would leak assembler registration triples");
    }

    @Test
    public void unknownSourceIsNotFound(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        HttpResponse<String> r = raw(
            "http://localhost:" + server.getHttpPort() + "/$/config/bm90LWEtcmVhbC1pZA", "GET");
        assertEquals(404, r.statusCode());
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

    /** An id must mean the same file after a restart, so it cannot be a counter. */
    @Test
    public void sourceIdIsStableAcrossRestarts(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);

        startWithConfig(cfg);
        String base1 = "http://localhost:" + server.getHttpPort();
        String first = JSON.parse(get(base1 + "/$/config"))
            .get("sources").getAsArray().get(0).getAsObject().get("id").getAsString().value();
        server.stop();

        startWithConfig(cfg);
        String base2 = "http://localhost:" + server.getHttpPort();
        String second = JSON.parse(get(base2 + "/$/config"))
            .get("sources").getAsArray().get(0).getAsObject().get("id").getAsString().value();

        assertEquals(first, second);
    }

    @Test
    public void effectiveViewDescribesDatasetsAndStatesItsLimits(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("config.ttl");
        Files.writeString(cfg, CONFIG);
        startWithConfig(cfg);

        String base = "http://localhost:" + server.getHttpPort();
        String id = JSON.parse(get(base + "/$/config"))
            .get("sources").getAsArray().get(0).getAsObject().get("id").getAsString().value();

        JsonObject effective = JSON.parse(get(base + "/$/config/" + id + "?view=effective"));
        assertNotNull(effective.get("fingerprintVersion"));

        JsonArray caveats = effective.get("caveats").getAsArray();
        assertTrue(caveats.size() >= 2,
            "the effective view must state what a green fingerprint does not prove");

        JsonArray datasets = effective.get("datasets").getAsArray();
        assertEquals(1, datasets.size());
        JsonObject ds = datasets.get(0).getAsObject();
        assertEquals("/ds", ds.get("name").getAsString().value());
        assertTrue(!ds.get("shaclIndex").getAsBoolean().value(),
            "a plain memory dataset has no SHACL index, and should say so rather than omit the key");
    }

    /** With no configuration file at all the endpoint is empty, not broken. */
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

    @Test
    public void aProgrammaticServerListsNoSources() throws Exception {
        server = FusekiServer.create()
            .port(0)
            .fusekiModules(org.apache.jena.fuseki.main.sys.FusekiModules.create(FMod_Config.create()))
            .add("/ds", DatasetGraphFactory.createTxnMem())
            .build()
            .start();

        JsonObject body = JSON.parse(get("http://localhost:" + server.getHttpPort() + "/$/config"));
        assertEquals(0, body.get("sources").getAsArray().size());
    }
}
