package com.stationannouncer.mtraddon.dispatch;

import org.mtr.libraries.javax.servlet.AsyncContext;
import org.mtr.libraries.javax.servlet.ServletOutputStream;
import org.mtr.libraries.javax.servlet.http.HttpServlet;
import org.mtr.libraries.javax.servlet.http.HttpServletRequest;
import org.mtr.libraries.javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GET {@code /dispatch/api/stream?dimension=N} — the live SSE stream (WebSocket is off
 * the table: MTR 4.0.1's shaded Jetty carries no websocket modules, so Server-Sent
 * Events over the relocated async servlet API is the substitute; {@code EventSource}
 * reconnects natively). This servlet extends the relocated {@code HttpServlet}
 * directly, NOT {@code ServletBase} — the response must stay open indefinitely, so
 * there is no simulator hop and no JSON envelope here.
 *
 * <p>Flow: validate registry + dimension + client cap → 200 with
 * {@code text/event-stream} headers → {@code startAsync()} with timeout 0 → flush a
 * comment + retry hint on the Jetty thread (so EventSource sees the open) → hand the
 * async context to {@link DispatchStreamer}, whose streamer thread performs every
 * subsequent write (blocking IO is safe from another thread because no WriteListener
 * is ever installed). The client receives a {@code full} event on the next streamer
 * tick, then {@code delta}s.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — never registered when off; HTTP 503 when
 * the registry is empty (server stopping) or {@code dispatch.maxClients} is reached.</p>
 */
public final class DispatchStreamServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        DispatchRegistry.State state = DispatchRegistry.get();
        if (state == null) {
            response.sendError(503, "Dispatch not available");
            return;
        }
        int dimensionIndex = 0;
        String dimensionParameter = request.getParameter("dimension");
        if (dimensionParameter != null && !dimensionParameter.isEmpty()) {
            try {
                dimensionIndex = Integer.parseInt(dimensionParameter);
            } catch (NumberFormatException e) {
                response.sendError(400, "Invalid dimension");
                return;
            }
        }
        if (dimensionIndex < 0 || dimensionIndex >= state.simulators().size()) {
            response.sendError(400, "Invalid dimension");
            return;
        }
        if (!DispatchStreamer.hasCapacity()) {
            response.sendError(503, "Too many dispatch clients");
            return;
        }

        response.setStatus(200);
        response.setHeader("Content-Type", "text/event-stream;charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("X-Accel-Buffering", "no");

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);
        ServletOutputStream outputStream = response.getOutputStream();
        // First bytes flushed on the servlet thread so the EventSource opens immediately.
        outputStream.write(": connected\nretry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        if (!DispatchStreamer.register(asyncContext, outputStream, dimensionIndex)) {
            // Cap was hit between the advisory check and registration; status is already
            // committed, so refuse in-band and close.
            try {
                outputStream.write("event: error\ndata: {\"error\":\"too many dispatch clients\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (IOException ignored) {
            }
            try {
                asyncContext.complete();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
