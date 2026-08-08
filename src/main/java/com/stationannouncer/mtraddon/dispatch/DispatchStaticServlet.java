package com.stationannouncer.mtraddon.dispatch;

import org.mtr.libraries.javax.servlet.http.HttpServlet;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the dispatch frontend's static files from the mod jar's classpath at
 * {@code assets/station_announcer/dispatch/}, mapped at {@code /dispatch/*} on MTR's
 * webserver. MTR's own {@code WebServlet} was considered (javap-verified) but its
 * content provider is {@code Function<String, String>} — text-only, so binary assets
 * (png/ico/woff2) would be corrupted; hence this small servlet with byte-accurate IO
 * and explicit MIME types.
 *
 * <p><b>Threading:</b> Jetty worker threads only; blocking IO (the classpath read is
 * in-jar and tiny). No MTR or Minecraft state is touched, so it works even while
 * {@link DispatchRegistry} is empty — the page itself then shows the API's 503s.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — when off the servlet is never registered.</p>
 */
public final class DispatchStaticServlet extends HttpServlet {
    private static final String BASE = "/assets/station_announcer/dispatch";

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html;charset=utf-8"),
            Map.entry("htm", "text/html;charset=utf-8"),
            Map.entry("js", "text/javascript;charset=utf-8"),
            Map.entry("mjs", "text/javascript;charset=utf-8"),
            Map.entry("css", "text/css;charset=utf-8"),
            Map.entry("json", "application/json;charset=utf-8"),
            Map.entry("map", "application/json;charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain;charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json")
    );

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }
        // Normalize + reject traversal; classpath lookup has no query strings to strip.
        if (path.contains("..") || path.contains("//") || path.indexOf('\\') >= 0) {
            response.sendError(404);
            return;
        }
        byte[] content = readResource(BASE + path);
        if (content == null && !path.contains(".")) {
            // Directory-style URL (e.g. /dispatch/somepage) → let the SPA's index handle it.
            path = "/index.html";
            content = readResource(BASE + path);
        }
        if (content == null) {
            response.sendError(404);
            return;
        }
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        response.setStatus(200);
        response.setHeader("Content-Type", MIME_TYPES.getOrDefault(extension, "application/octet-stream"));
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
    }

    private static byte[] readResource(String resourcePath) throws IOException {
        try (InputStream stream = DispatchStaticServlet.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.max(1024, stream.available()));
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    /** Shared helper for tiny synchronous JSON/text replies from the dispatch servlets. */
    static void sendText(HttpServletResponse response, int status, String contentType, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            response.setStatus(status);
            response.setHeader("Content-Type", contentType);
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } catch (IOException ignored) {
            // Client went away mid-reply; nothing to clean up for a sync response.
        }
    }
}
