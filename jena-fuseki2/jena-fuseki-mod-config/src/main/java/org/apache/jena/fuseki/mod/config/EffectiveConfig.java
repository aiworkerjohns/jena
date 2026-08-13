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

import org.apache.jena.atlas.json.JsonBuilder;
import org.apache.jena.fuseki.server.DataAccessPoint;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.query.text.ShaclConfigFingerprint;
import org.apache.jena.query.text.ShaclIndexMapping;
import org.apache.jena.query.text.ShaclIndexStamp;
import org.apache.jena.query.text.ShaclTextIndexLucene;
import org.apache.jena.query.text.TextIndex;
import org.apache.jena.query.text.DatasetGraphText;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;

/**
 * What the server actually resolved, as opposed to what the file says.
 * <p>
 * This is the part a client cannot compute for itself by fetching the Turtle and parsing
 * it. Jena fills in defaults during assembly — {@code text:taxonomyDirectory} derived from
 * {@code text:directory} is one — and the index-configuration fingerprint depends on the
 * parsed {@link ShaclIndexMapping}, not on the text of the file. A browser-side parser
 * re-implements those rules and drifts from them.
 * <p>
 * Built from the live objects in the {@link DataAccessPointRegistry}, so it describes the
 * running server rather than a re-reading of the configuration.
 */
class EffectiveConfig {

    private EffectiveConfig() {}

    static void write(HttpAction action, ConfigSources.Source source) throws IOException {
        JsonBuilder b = new JsonBuilder();
        b.startObject();
        b.pair("id", source.id());
        b.pair("path", source.path());
        b.pair("kind", source.kind());
        b.pair("fingerprintVersion", ShaclConfigFingerprint.FINGERPRINT_VERSION);

        b.key("caveats").startArray();
        b.value("Analyzer parameters (for example a stopword list) are not covered by the "
                + "configuration fingerprint; only the analyzer class is.");
        b.value("A fingerprint match means the index was built from an equivalent "
                + "configuration. It does not prove these files were produced by this "
                + "configuration file; the dataset pairing covers that.");
        b.finishArray();

        b.key("datasets").startArray();
        DataAccessPointRegistry registry = action.getDataAccessPointRegistry();
        if ( registry != null ) {
            for ( DataAccessPoint dap : registry.accessPoints() )
                describeDataset(b, dap);
        }
        b.finishArray();

        b.finishObject();
        ActionConfig.writeJson(action, b.build());
    }

    private static void describeDataset(JsonBuilder b, DataAccessPoint dap) {
        b.startObject();
        b.pair("name", dap.getName());

        DatasetGraph dsg = dap.getDataService().getDataset();
        ShaclTextIndexLucene index = findShaclIndex(dsg);
        if ( index == null ) {
            // Not every dataset is text-indexed; say so rather than omitting the key,
            // so a client can tell "no index" from "field missing".
            b.pair("shaclIndex", false);
            b.finishObject();
            return;
        }
        b.pair("shaclIndex", true);
        describeIndexStatus(b, index);
        describeMapping(b, index.getShaclMapping());
        b.finishObject();
    }

    private static void describeIndexStatus(JsonBuilder b, ShaclTextIndexLucene index) {
        b.key("index").startObject();
        b.pair("runningFingerprint", index.getConfigFingerprint());
        ShaclIndexStamp.StampData stamp = index.getOpenedStamp();
        b.pair("status", index.getConfigStatus().name());
        if ( stamp != null ) {
            b.pair("builtFingerprint", stamp.fingerprint());
            if ( stamp.builtAt() != null )
                b.pair("builtAt", stamp.builtAt());
            if ( stamp.jenaVersion() != null )
                b.pair("builtBy", stamp.jenaVersion());
            if ( stamp.indexInstanceId() != null )
                b.pair("indexInstanceId", stamp.indexInstanceId());
            if ( stamp.pairedDatasetId() != null )
                b.pair("pairedDatasetId", stamp.pairedDatasetId());
        }
        b.finishObject();
    }

    private static void describeMapping(JsonBuilder b, ShaclIndexMapping mapping) {
        if ( mapping == null )
            return;
        b.key("profiles").startArray();
        for ( ShaclIndexMapping.IndexProfile profile : mapping.getProfiles() ) {
            b.startObject();
            b.key("targetClasses").startArray();
            for ( var cls : profile.getTargetClasses() )
                b.value(cls.isURI() ? cls.getURI() : cls.toString());
            b.finishArray();

            b.key("fields").startArray();
            for ( ShaclIndexMapping.FieldDef f : profile.getFields() ) {
                b.startObject()
                 .pair("name", f.getFieldName())
                 .pair("type", f.getFieldType().name())
                 .pair("stored", f.isStored())
                 .pair("indexed", f.isIndexed())
                 .pair("facetable", f.isFacetable())
                 .pair("sortable", f.isSortable())
                 .pair("multiValued", f.isMultiValued())
                 .pair("defaultSearch", f.isDefaultSearch());
                if ( f.getFieldIRI() != null && f.getFieldIRI().isURI() )
                    b.pair("iri", f.getFieldIRI().getURI());
                b.finishObject();
            }
            b.finishArray();

            b.key("hierarchies").startArray();
            for ( ShaclIndexMapping.HierarchyDef h : profile.getHierarchies() ) {
                b.startObject().pair("dimension", h.getDimensionName());
                b.key("levels").startArray();
                for ( ShaclIndexMapping.FieldDef level : h.getLevels() )
                    b.value(level.getFieldName());
                b.finishArray();
                b.finishObject();
            }
            b.finishArray();
            b.finishObject();
        }
        b.finishArray();
    }

    /**
     * Find the SHACL index behind a dataset, looking through the wrapper layers that text
     * indexing adds. Returns null for a dataset that is not SHACL text-indexed.
     */
    private static ShaclTextIndexLucene findShaclIndex(DatasetGraph dsg) {
        DatasetGraph current = dsg;
        // Bounded rather than while(true): a cyclic wrapper chain must not hang a request.
        for ( int depth = 0 ; current != null && depth < 20 ; depth++ ) {
            if ( current instanceof DatasetGraphText text ) {
                TextIndex ti = text.getTextIndex();
                return (ti instanceof ShaclTextIndexLucene shacl) ? shacl : null;
            }
            if ( current instanceof DatasetGraphWrapper wrapper ) {
                current = wrapper.getWrapped();
                continue;
            }
            return null;
        }
        return null;
    }
}
