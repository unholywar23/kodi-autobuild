package com.boxtools.adbrescue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Tiny hand-rolled GET-only HTTP server on 0.0.0.0:9094.
 *   /ping   -> {"ok":true}
 *   /status -> full state + ring log
 *   /toggle -> force a toggle now (PC calls only when adb is already dead)
 *   /nudge  -> fire the ADB Auto-Enable /api/test call now
 */
public final class HttpServer implements Runnable {
    public static final int PORT = 9094;

    private final RescueService svc;
    private final RingLog log;

    public HttpServer(RescueService svc, RingLog log) {
        this.svc = svc;
        this.log = log;
    }

    @Override
    public void run() {
        while (true) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", PORT));
                log.add("http: listening on 0.0.0.0:" + PORT);
                while (!server.isClosed()) {
                    final Socket c = server.accept();
                    Thread t = new Thread(() -> serve(c));
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Throwable t) {
                log.add("http: server error: " + t);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) {
                c.close();
                return;
            }
            // Drain headers (we accept GET only, no body).
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) { /* skip */ }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (!"GET".equalsIgnoreCase(method)) {
                respond(c, "405 Method Not Allowed", "{\"error\":\"GET only\"}");
                return;
            }

            String body;
            switch (path) {
                case "/ping":
                    body = "{\"ok\":true}";
                    break;
                case "/status":
                    body = svc.statusJson();
                    break;
                case "/toggle":
                    svc.requestForcedToggle();
                    body = "{\"ok\":true,\"action\":\"toggle-requested\"}";
                    break;
                case "/nudge":
                    svc.requestNudge();
                    body = "{\"ok\":true,\"action\":\"nudge-requested\"}";
                    break;
                default:
                    respond(c, "404 Not Found", "{\"error\":\"unknown path\"}");
                    return;
            }
            respond(c, "200 OK", body);
        } catch (Throwable t) {
            try { respond(c, "500 Internal Server Error", "{\"error\":\"server\"}"); }
            catch (Throwable ignored) {}
        } finally {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }

    private void respond(Socket c, String status, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Content-Type: application/json; charset=utf-8\r\n");
        head.append("Content-Length: ").append(b.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("\r\n");
        OutputStream os = c.getOutputStream();
        os.write(head.toString().getBytes(StandardCharsets.UTF_8));
        os.write(b);
        os.flush();
    }
}
