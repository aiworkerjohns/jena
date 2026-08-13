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
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.jena.fuseki.mgt.FusekiServerCtl;

/**
 * The configuration files a running server was built from.
 *
 * <h3>Why sources, not datasets</h3>
 *
 * Fuseki reads configuration from two places with different shapes. A {@code --config}
 * file holds a {@code fuseki:Server} and any number of services. The
 * {@code FUSEKI_BASE/configuration/} directory holds one file per service, each parsed
 * into its own graph — {@code FusekiConfig.readConfigurationDirectory} gives every file a
 * fresh {@code Graph} and {@code DatasetDescriptionMap}, so nothing is merged and a prefix
 * declared in one file is invisible in another.
 * <p>
 * That makes the dataset-to-file relation many-to-one in the first case and one-to-one in
 * the second, so the file is the honest unit to key on.
 */
public class ConfigSources {

    /**
     * @param id       opaque, URL-safe, derived from the path so it is stable across restarts
     * @param kind     {@code "server"} for {@code --config}, {@code "directory"} for a
     *                 file in {@code FUSEKI_BASE/configuration/}
     * @param path     absolute path, for display
     * @param readable whether the file can currently be read
     */
    public record Source(String id, String kind, String path, boolean readable) {}

    private ConfigSources() {}

    /**
     * Every configuration file this server could have read.
     * <p>
     * The server config comes from {@code FusekiServer.getConfigFilename()}, the only
     * thing Fuseki retains — the parsed model is dropped after the modules see it. The
     * directory is listed fresh rather than remembered, so a file added since startup is
     * shown; it is marked as such by the server not knowing about its datasets.
     */
    public static List<Source> list(String serverConfigFilename) {
        List<Source> sources = new ArrayList<>();
        if ( serverConfigFilename != null ) {
            Path p = Path.of(serverConfigFilename).toAbsolutePath().normalize();
            sources.add(toSource(p, "server"));
        }
        Path configDir = FusekiServerCtl.dirConfiguration;
        if ( configDir != null && Files.isDirectory(configDir) ) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
                List<Path> entries = new ArrayList<>();
                stream.forEach(entries::add);
                // Directory order is filesystem-dependent; sort so the listing is stable.
                entries.sort(null);
                for ( Path p : entries ) {
                    if ( Files.isRegularFile(p) )
                        sources.add(toSource(p.toAbsolutePath().normalize(), "directory"));
                }
            } catch (IOException e) {
                // A listing failure should not take out the endpoint; the server config
                // is usually the interesting one anyway.
            }
        }
        return sources;
    }

    /** Find a source by its id, or null. */
    public static Source find(String serverConfigFilename, String id) {
        for ( Source s : list(serverConfigFilename) ) {
            if ( s.id().equals(id) )
                return s;
        }
        return null;
    }

    /**
     * The file's bytes, as text.
     * <p>
     * Deliberately the bytes on disk rather than a re-serialisation of the parsed model:
     * {@code AssemblerUtils.readAssemblerFile} adds {@code modelExtras} — the
     * {@code rdfs:subClassOf} triples that assembler registration contributes — so writing
     * the model back out would emit triples the user never wrote, and would lose every
     * comment and prefix choice in the file.
     */
    public static String read(Source source) throws IOException {
        return Files.readString(Path.of(source.path()), StandardCharsets.UTF_8);
    }

    /**
     * An id derived from the absolute path.
     * <p>
     * URL-safe base64 rather than the path itself: a path contains {@code /} and would
     * need escaping in the request URI, and rather than a counter because an id must
     * survive a restart and mean the same file.
     */
    private static Source toSource(Path path, String kind) {
        String id = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
        return new Source(id, kind, path.toString(), Files.isReadable(path));
    }
}
