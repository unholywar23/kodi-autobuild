package com.boxtools.adbrescue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Listens on 0.0.0.0:5556 and forwards each inbound connection to whatever
 * adbd endpoint is currently live:
 *   - 127.0.0.1:5555 if the legacy tcpip port is open, else
 *   - wlanIP:tlsPort if a wireless-debugging TLS server is up and reachable.
 * This makes the Wi-Fi-only random TLS port reachable over the Tailscale
 * address: the PC runs `adb connect 100.103.108.33:5556`.
 */
public final class Relay implements Runnable {
    public static final int LISTEN_PORT = 5556;

    private final RescueService svc;
    private final RingLog log;
    private volatile String lastTarget = "none";
    private ServerSocket server;

    public Relay(RescueService svc, RingLog log) {
        this.svc = svc;
        this.log = log;
    }

    public String lastTarget() {
        return lastTarget;
    }

    @Override
    public void run() {
        while (true) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", LISTEN_PORT));
                log.add("relay: listening on 0.0.0.0:" + LISTEN_PORT);
                while (!server.isClosed()) {
                    Socket in = server.accept();
                    handle(in);
                }
            } catch (Throwable t) {
                log.add("relay: server error: " + t);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handle(final Socket in) {
        String host = null;
        int port = -1;
        if (svc.isPortOpen("127.0.0.1", 5555, 200)) {
            host = "127.0.0.1";
            port = 5555;
        } else {
            String wlan = svc.getWlanIp();
            int tls = svc.getTlsPort();
            if (wlan != null && tls > 0 && svc.isPortOpen(wlan, tls, 200)) {
                host = wlan;
                port = tls;
            }
        }
        if (host == null) {
            lastTarget = "none";
            log.add("relay: no live adb target, closing inbound " + in.getRemoteSocketAddress());
            closeQuietly(in);
            return;
        }
        final String target = host + ":" + port;
        lastTarget = target;
        final Socket out = new Socket();
        try {
            out.connect(new InetSocketAddress(host, port), 2000);
        } catch (Throwable t) {
            log.add("relay: connect to " + target + " failed: " + t);
            closeQuietly(in);
            closeQuietly(out);
            return;
        }
        log.add("relay: " + in.getRemoteSocketAddress() + " <-> " + target);
        pump(in, out);
        pump(out, in);
    }

    /** One direction, one thread; closes both ends when the stream ends. */
    private void pump(final Socket from, final Socket to) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                InputStream is = from.getInputStream();
                OutputStream os = to.getOutputStream();
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    os.flush();
                }
            } catch (Throwable ignored) {
                // normal on close
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }
}
