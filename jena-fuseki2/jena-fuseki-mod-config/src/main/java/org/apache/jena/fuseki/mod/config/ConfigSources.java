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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.fuseki.mgt.FusekiServerCtl;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;

/**
 * The configuration files a running server was built from, captured as they were when it
 * started.
 *
 * <h3>Two different things, both called "config"</h3>
 *
 * Fuseki uses the word for two unrelated files, and conflating them is the main source of
 * confusion in this area:
 *
 * <ul>
 * <li><b>The server configuration</b> — the single {@code --config} file. Holds
 *     {@code fuseki:Server}, so it is the only place server-wide settings such as
 *     timeouts can live, plus any number of services. There is at most one.</li>
 * <li><b>Dataset configurations</b> — {@code FUSEKI_BASE/configuration/*.ttl}, one file
 *     per dataset. {@code FusekiConfig.readConfigurationDirectory} parses each into its
 *     own graph with its own {@code DatasetDescriptionMap}, so nothing is merged and a
 *     prefix declared in one is invisible in another. They carry no
 *     {@code fuseki:Server}, so no server-wide setting can come from one.</li>
 * </ul>
 *
 * A dataset configuration is keyed by dataset name, not by an opaque handle:
 * {@code FusekiServerCtl.generateConfigurationFilename} writes {@code <dsName>.ttl}, so
 * the filename stem is the dataset name.
 *
 * <h3>Why a snapshot rather than reading the file per request</h3>
 *
 * The promise is "the configuration this server is running". A file on disk does not keep
 * it: Fuseki reads its configuration once at startup and never looks again, so editing
 * afterwards changes the file and nothing about the running server. Serving live meant
 * showing an edit the server had never seen while the effective view alongside still
 * reported the old, actually running fingerprint.
 * <p>
 * So the bytes are captured at startup. The current file is still read, but only to
 * answer a different question: {@link Source#changedOnDisk()} says whether it has been
 * edited since — useful to be told, dangerous to be shown without being told.
 */
public class ConfigSources {

    /**
     * @param name dataset name for a dataset configuration; null for the server configuration
     * @param path absolute path, for display
     * @param text the bytes as the server read them, or null if unreadable at startup
     */
    public record Source(String name, String path, String text) {

        public boolean readable() {
            return text != null;
        }

        /**
         * Whether the file now differs from what the server loaded. False when the file
         * cannot be read now — a vanished file is reported through
         * {@link #onDiskReadable()}, not as a content change.
         */
        public boolean changedOnDisk() {
            String current = currentText();
            return current != null && !current.equals(text);
        }

        public boolean onDiskReadable() {
            return currentText() != null;
        }

        /** The file's current content, or null if it cannot be read. */
        public String currentText() {
            try {
                return Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        /** RIOT content type for the file's extension, defaulting to Turtle. */
        public String contentType() {
            Lang lang = RDFLanguages.filenameToLang(path);
            return lang != null ? lang.getContentType().getContentTypeStr()
                                : Lang.TURTLE.getContentType().getContentTypeStr();
        }
    }

    /**
     * Everything a server was configured from, captured once during construction.
     *
     * @param server   the {@code --config} file, or null if there was none
     * @param datasets dataset configurations by dataset name, in filename order
     */
    public record Captured(Source server, Map<String, Source> datasets) {
        static final Captured EMPTY = new Captured(null, Map.of());
    }

    private ConfigSources() {}

    /**
     * Capture every configuration file this server was built from.
     * <p>
     * The directory is listed at the same moment, so a file added later is absent —
     * correct, because the server has not read it either.
     */
    public static Captured capture(String serverConfigFilename) {
        Source server = null;
        if ( serverConfigFilename != null ) {
            Path p = Path.of(serverConfigFilename).toAbsolutePath().normalize();
            server = toSource(null, p);
        }

        Map<String, Source> datasets = new LinkedHashMap<>();
        Path configDir = FusekiServerCtl.dirConfiguration;
        if ( configDir != null && Files.isDirectory(configDir) ) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
                List<Path> entries = new ArrayList<>();
                stream.forEach(entries::add);
                // Directory order is filesystem-dependent; sort so the listing is stable.
                entries.sort(null);
                for ( Path p : entries ) {
                    if ( !Files.isRegularFile(p) )
                        continue;
                    String name = datasetName(p);
                    // First wins. Two files differing only by extension would be a
                    // duplicate dataset, which Fuseki itself refuses at startup.
                    datasets.putIfAbsent(name, toSource(name, p.toAbsolutePath().normalize()));
                }
            } catch (IOException e) {
                // A listing failure should not take out the endpoint; the server
                // configuration is usually the interesting one anyway.
            }
        }
        return new Captured(server, Collections.unmodifiableMap(datasets));
    }

    /** Dataset name from a configuration filename: the stem, per {@code generateConfigurationFilename}. */
    private static String datasetName(Path file) {
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static Source toSource(String name, Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = null;
        }
        return new Source(name, path.toString(), text);
    }
}
