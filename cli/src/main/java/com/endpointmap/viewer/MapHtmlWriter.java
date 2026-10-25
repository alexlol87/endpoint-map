package com.endpointmap.viewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes map.html: the viewer template with Cytoscape.js, dagre and the graph inlined, so it works offline. */
public final class MapHtmlWriter {

    private MapHtmlWriter() {
    }

    public static void write(String graphJson, Path file) throws IOException {
        String html = resource("map.html")
                .replace("/*@CYTOSCAPE@*/", resource("cytoscape.min.js"))
                .replace("/*@DAGRE@*/", resource("dagre.min.js"))
                .replace("/*@CYTOSCAPE_DAGRE@*/", resource("cytoscape-dagre.js"))
                // "</" inside a string would end the <script> element early
                .replace("/*@GRAPH@*/null", graphJson.replace("</", "<\\/"));
        Files.writeString(file, html);
    }

    private static String resource(String name) {
        try (InputStream in = MapHtmlWriter.class.getResourceAsStream("/viewer/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing viewer resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
