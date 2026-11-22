package com.boxtools.adbrescue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rescue loop. Watches adb-over-Wi-Fi state and re-triggers the
 * AdbDebuggingManager by toggling Settings.Global.adb_wifi_enabled 1->0->1,
 * the only write that actually re-fires the enable path. Runs forever as a
 * START_STICKY foreground service.
 */
public class RescueService extends Service {
    private static final String VERSION = "1.0";
    private static final String CHANNEL_ID = "adb-rescue";
    private static final int NOTIF_ID = 1;

    private static final long LOOP_MS = 20_000L;
    private static final long TOGGLE_MIN_GAP_MS = 60_000L;        // min 60s between toggles
    private static final long TOGGLE_BACKOFF_GAP_MS = 120_000L;   // 2 min after 5 tries
    private static final int  TOGGLE_BACKOFF_AFTER = 5;
    private static final long NUDGE_MIN_GAP_MS = 180_000L;        // at most once / 3 min
    private static final long TLS_CACHE_MS = 180_000L;            // cache discovered TLS port 3 min

    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final String AAE_TEST_URL = "http://127.0.0.1:9093/api/test";

    private final RingLog log = new RingLog();

    private volatile boolean running = false;
    private Thread loopThread;
    private Relay relay;

    private final long startedAt = System.currentTimeMillis();

    // Toggle bookkeeping
    private volatile long lastToggleAt = 0L;
    private volatile int toggleCount = 0;
    private volatile int toggleTries = 0;          // consecutive heal attempts without success
    private volatile boolean forcedToggleRequested = false;

    // Nudge bookkeeping
    private volatile long lastNudgeAt = 0L;
    private volatile boolean forcedNudgeRequested = false;

    // Discovered / detected state (last cycle)
    private volatile boolean wifiConnected = false;
    private volatile String wlanIp = null;
    private volatile int adbWifiEnabled = -1;       // Settings.Global value, -1 = unknown
    private volatile boolean port5555Open = false;
    private volatile int tlsPort = 0;               // 0 = unknown/none
    private volatile long tlsPortFoundAt = 0L;
    private volatile boolean tlsOpen = false;
    private volatile String lastHeal = "none";

    // NSD
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean nsdDiscovering = false;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundSafe();
        startRelay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe();
        startLoop();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ---- foreground + notification ------------------------------------------

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "adb-rescue", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification n;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                n = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("adb-rescue")
                        .setContentText("watching adb-over-Wi-Fi")
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setOngoing(true)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("adb-rescue")
                        .setContentText("watching adb-over-Wi-Fi")
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setOngoing(true)
                        .build();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            log.add("foreground start error: " + t);
        }
    }

    // ---- relay --------------------------------------------------------------

    private synchronized void startRelay() {
        if (relay != null) return;
        relay = new Relay(this, log);
        Thread rt = new Thread(relay, "relay");
        rt.setDaemon(true);
        rt.start();
        Thread ht = new Thread(new HttpServer(this, log), "http");
        ht.setDaemon(true);
        ht.start();
    }

    // ---- main loop ----------------------------------------------------------

    private synchronized void startLoop() {
        if (running) return;
        running = true;
        loopThread = new Thread(() -> {
            log.add("rescue service started, version " + VERSION);
            while (running) {
                try {
                    cycle();
                } catch (Throwable t) {
                    log.add("cycle error: " + t);
                }
                try {
                    Thread.sleep(LOOP_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }, "rescue-loop");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    private void cycle() {
        // --- gather state ---
        wifiConnected = detectWifi();
        wlanIp = detectWlanIp();
        adbWifiEnabled = readAdbWifiEnabled();
        port5555Open = isPortOpen("127.0.0.1", 5555, 200);
        refreshTlsPort();
        tlsOpen = (wlanIp != null && tlsPort > 0) && isPortOpen(wlanIp, tlsPort, 200);

        boolean adbListening = port5555Open || tlsOpen;

        // --- forced actions from HTTP ---
        if (forcedToggleRequested) {
            forcedToggleRequested = false;
            log.add("forced /toggle: toggling regardless of listen state");
            doToggle("forced");
        }
        if (forcedNudgeRequested) {
            forcedNudgeRequested = false;
            log.add("forced /nudge");
            nudge();
        }

        // --- nudge: TLS up but 5555 still closed -> ask ADB Auto-Enable to move to 5555 ---
        if (tlsOpen && !port5555Open) {
            long now = System.currentTimeMillis();
            if (now - lastNudgeAt >= NUDGE_MIN_GAP_MS) {
                log.add("TLS up but 5555 closed -> nudging ADB Auto-Enable");
                nudge();
            }
        }

        // --- heal decision ---
        if (!wifiConnected) {
            lastHeal = "skip: wifi down";
            return;
        }
        if (adbListening) {
            // adbd is live; never toggle (would cut a live connection). Reset try counter.
            toggleTries = 0;
            lastHeal = port5555Open ? "ok: 5555 open" : "ok: TLS open";
            return;
        }
        // Wi-Fi up, 5555 closed, no TLS server listening -> consider toggling.
        long now = System.currentTimeMillis();
        long gap = (toggleTries >= TOGGLE_BACKOFF_AFTER) ? TOGGLE_BACKOFF_GAP_MS : TOGGLE_MIN_GAP_MS;
        if (now - lastToggleAt < gap) {
            lastHeal = "wait: " + ((gap - (now - lastToggleAt)) / 1000) + "s to next toggle";
            return;
        }
        toggleTries++;
        doToggle("heal #" + toggleTries);
    }

    // ---- actions ------------------------------------------------------------

    /** Toggle adb_wifi_enabled 0 then 1 to re-fire AdbDebuggingManager. */
    private void doToggle(String why) {
        try {
            Settings.Global.putInt(getContentResolver(), ADB_WIFI_ENABLED, 0);
            log.add("toggle (" + why + "): wrote adb_wifi_enabled=0");
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            Settings.Global.putInt(getContentResolver(), ADB_WIFI_ENABLED, 1);
            log.add("toggle (" + why + "): wrote adb_wifi_enabled=1");
            lastToggleAt = System.currentTimeMillis();
            toggleCount++;
            lastHeal = "toggled (" + why + ")";
        } catch (SecurityException se) {
            log.add("toggle FAILED: WRITE_SECURE_SETTINGS not granted? " + se);
            lastHeal = "toggle failed: no permission";
        } catch (Throwable t) {
            log.add("toggle error: " + t);
            lastHeal = "toggle error";
        }
    }

    /** GET http://127.0.0.1:9093/api/test to nudge ADB Auto-Enable to move adbd to 5555. */
    private void nudge() {
        lastNudgeAt = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL u = new URL(AAE_TEST_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            log.add("nudge: ADB Auto-Enable /api/test -> HTTP " + code);
        } catch (Throwable t) {
            log.add("nudge error (is ADB Auto-Enable running on 9093?): " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void requestForcedToggle() {
        forcedToggleRequested = true;
    }

    public void requestNudge() {
        forcedNudgeRequested = true;
    }

    // ---- state detection ----------------------------------------------------

    /** True if any network has TRANSPORT_WIFI (the active net is the VPN with always-on). */
    private boolean detectWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return true;
                }
            }
            // Fallback: WifiManager says Wi-Fi is enabled and has an IP.
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                return detectWlanIp() != null;
            }
        } catch (Throwable t) {
            log.add("wifi detect error: " + t);
        }
        return false;
    }

    /** The IPv4 of the Wi-Fi interface (not the VPN). Enumerate Wi-Fi network link addrs. */
    private String detectWlanIp() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network net : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                String ip = la.getAddress().getHostAddress();
                                if (ip != null && ip.indexOf(':') < 0) { // IPv4
                                    return ip;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log.add("wlan ip detect error: " + t);
        }
        return null;
    }

    private int readAdbWifiEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), ADB_WIFI_ENABLED, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Find the TLS port: getprop first, then cached NSD, else start NSD discovery. */
    private void refreshTlsPort() {
        // 1) getprop service.adb.tls.port (empty when adbd is in tcpip mode)
        int fromProp = getpropInt("service.adb.tls.port");
        if (fromProp > 0) {
            tlsPort = fromProp;
            tlsPortFoundAt = System.currentTimeMillis();
            stopNsd();
            return;
        }
        // 2) cached NSD result still fresh?
        if (tlsPort > 0 && System.currentTimeMillis() - tlsPortFoundAt < TLS_CACHE_MS) {
            return;
        }
        // cache expired
        if (tlsPort > 0 && System.currentTimeMillis() - tlsPortFoundAt >= TLS_CACHE_MS) {
            tlsPort = 0;
        }
        // 3) start NSD discovery of _adb-tls-connect._tcp
        startNsd();
    }

    private int getpropInt(String key) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            if (line != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    return Integer.parseInt(line);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return 0;
    }

    private synchronized void startNsd() {
        if (nsdDiscovering) return;
        try {
            if (nsdManager == null) {
                nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            }
            if (nsdManager == null) return;
            acquireMulticast();
            nsdDiscovering = true;
            nsdManager.discoverServices("_adb-tls-connect._tcp",
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            log.add("nsd: discovery started for _adb-tls-connect._tcp");
        } catch (Throwable t) {
            nsdDiscovering = false;
            log.add("nsd start error: " + t);
        }
    }

    private synchronized void stopNsd() {
        if (!nsdDiscovering) {
            releaseMulticast();
            return;
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Throwable ignored) {
        }
        nsdDiscovering = false;
        releaseMulticast();
    }

    private void acquireMulticast() {
        try {
            if (multicastLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("adb-rescue-nsd");
                    multicastLock.setReferenceCounted(false);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseMulticast() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private final NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
        @Override public void onStartDiscoveryFailed(String t, int e) { nsdDiscovering = false; }
        @Override public void onStopDiscoveryFailed(String t, int e) { }
        @Override public void onDiscoveryStarted(String t) { }
        @Override public void onDiscoveryStopped(String t) { }
        @Override public void onServiceLost(NsdServiceInfo s) { }

        @Override
        public void onServiceFound(NsdServiceInfo s) {
            try {
                nsdManager.resolveService(s, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo si, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo si) {
                        int port = si.getPort();
                        if (port > 0) {
                            tlsPort = port;
                            tlsPortFoundAt = System.currentTimeMillis();
                            log.add("nsd: resolved TLS port " + port);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    };

    // ---- shared helpers -----------------------------------------------------

    /** 200ms-style TCP connect probe. */
    public boolean isPortOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    public String getWlanIp() { return wlanIp; }
    public int getTlsPort() { return tlsPort; }

    // ---- status JSON --------------------------------------------------------

    public String statusJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("version", VERSION);
        m.put("uptimeMs", System.currentTimeMillis() - startedAt);
        m.put("wifiConnected", wifiConnected);
        m.put("wlanIp", wlanIp);
        m.put("adbWifiEnabled", adbWifiEnabled);
        m.put("port5555Open", port5555Open);
        m.put("tlsPort", tlsPort == 0 ? null : tlsPort);
        m.put("tlsOpen", tlsOpen);
        m.put("relayListenPort", Relay.LISTEN_PORT);
        m.put("relayTarget", relay != null ? relay.lastTarget() : "none");
        m.put("httpPort", HttpServer.PORT);
        m.put("lastToggleAt", lastToggleAt == 0 ? null : lastToggleAt);
        m.put("toggleCount", toggleCount);
        m.put("toggleTries", toggleTries);
        m.put("lastNudgeAt", lastNudgeAt == 0 ? null : lastNudgeAt);
        m.put("lastHeal", lastHeal);
        List<Object> logLines = new ArrayList<>(log.snapshot());
        m.put("log", logLines);
        return Json.val(m);
    }
}
