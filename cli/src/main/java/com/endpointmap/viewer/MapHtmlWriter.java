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
                // <, >, & only occur inside JSON strings: \\u escapes keep "</script>" and "<!--" from reaching the HTML parser
                .replace("/*@GRAPH@*/null", graphJson.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"));
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
