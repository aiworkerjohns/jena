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
import java.util.Collections;
import java.util.List;

import org.apache.jena.fuseki.mgt.FusekiServerCtl;

/**
 * The configuration files a running server was built from, captured as they were when it
 * started.
 *
 * <h3>Why a snapshot rather than reading the file per request</h3>
 *
 * The endpoint's promise is "the configuration this server is running". A file on disk
 * does not keep that promise: Fuseki reads its configuration once at startup and never
 * looks again, so editing the file afterwards changes what is on disk and nothing about
 * what is running. Serving the file live meant the endpoint showed an edit the server had
 * never seen, while the effective view alongside it still reported the old, actually
 * running fingerprint — a configuration viewer quietly disagreeing with itself.
 * <p>
 * So the bytes are captured at startup and served from memory. The current file is still
 * read, but only to answer a different question: {@link Source#changedOnDisk()} says
 * whether someone has edited it since, which is a useful thing to be told and a dangerous
 * thing to be shown without saying so.
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
     * @param text     the bytes as they were when the server read them, or null if the
     *                 file could not be read at startup
     */
    public record Source(String id, String kind, String path, String text) {

        public boolean readable() {
            return text != null;
        }

        /**
         * Whether the file now differs from what the server loaded.
         * <p>
         * False when the file cannot be read now — a vanished or unreadable file is
         * reported through {@link #onDiskReadable()} rather than as a content change.
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
    }

    private ConfigSources() {}

    /**
     * Capture every configuration file this server was built from. Called once, during
     * server construction.
     * <p>
     * The server config comes from the {@code --config} name; Fuseki itself retains only
     * that, and not always (see {@link FMod_Config}). The directory is listed at the same
     * moment, so a file added later is absent from the listing — correct, because the
     * server has not read it either.
     */
    public static List<Source> capture(String serverConfigFilename) {
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
        return Collections.unmodifiableList(sources);
    }

    /** Find a captured source by its id, or null. */
    public static Source find(List<Source> sources, String id) {
        for ( Source s : sources ) {
            if ( s.id().equals(id) )
                return s;
        }
        return null;
    }

    /**
     * An id derived from the absolute path.
     * <p>
     * URL-safe base64 rather than the path itself: a path contains {@code /} and would
     * need escaping in the request URI, and rather than a counter because an id must
     * survive a restart and mean the same file. Because a request is resolved by matching
     * against this captured list, a caller cannot name a path the server did not read.
     */
    private static Source toSource(Path path, String kind) {
        String id = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = null;
        }
        return new Source(id, kind, path.toString(), text);
    }
}
