package org.example.mininative;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

public final class MdnsDiscoverer {
    private static final String TAG = "MDNS";

    // Simple callback: give host, port, and raw TXT[] lines (arrival order preserved)
    public interface Callback {
        void onService(InetAddress host, int port, String[] txt);
    }

    private final Context appCtx;
    private final String serviceType;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Callback cb;

    private WifiManager.MulticastLock mlock;
    private JmDNS jmdns;
    private final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());

    public MdnsDiscoverer(Context ctx, String serviceType, Callback cb) {
        this.appCtx = ctx.getApplicationContext();
        this.serviceType = (serviceType != null) ? serviceType : Config.MDNS_SRV_NAME;
        this.cb = cb;
    }

    public void start() {
        io.execute(new Runnable() {
            @Override public void run() {
                if (jmdns != null) return;
                try {
                    acquireMulticastLockSafe();
                    InetAddress bind = chooseWifiIPv4();
                    if (bind != null) {
                        Log.i(TAG, "JmDNS.create(bind=" + bind.getHostAddress() + ")");
                        jmdns = JmDNS.create(bind);
                    } else {
                        Log.w(TAG, "No Wi-Fi IPv4 found, falling back to default bind");
                        jmdns = JmDNS.create();
                    }
                    jmdns.addServiceListener(serviceType, listener);
                    Log.i(TAG, "mDNS start ok type=" + serviceType);
                } catch (Throwable t) {
                    Log.e(TAG, "mDNS start failed", t);
                    safeClose();
                    releaseMulticastLockSafe();
                }
            }
        });
    }

    public void stop() {
        io.execute(new Runnable() {
            @Override public void run() {
                safeClose();
                releaseMulticastLockSafe();
                Log.i(TAG, "mDNS stopped");
            }
        });
    }

    private void safeClose() {
        try { if (jmdns != null) jmdns.close(); } catch (Throwable ignore) {}
        jmdns = null;
        seen.clear();
    }

    private final ServiceListener listener = new ServiceListener() {
        @Override public void serviceAdded(ServiceEvent e) {
            try { if (jmdns != null) jmdns.requestServiceInfo(e.getType(), e.getName(), true); }
            catch (Throwable t) { Log.w(TAG, "req resolve fail", t); }
            Log.i(TAG, "add: " + e.getName());
        }

        @Override public void serviceRemoved(ServiceEvent e) {
            Log.i(TAG, "rm : " + e.getName());
        }

        @Override public void serviceResolved(ServiceEvent e) {
            try {
                ServiceInfo info = e.getInfo();
                if (info == null) return;

                // prefer IPv4
                InetAddress host4 = null, hostAny = null;
                InetAddress[] addrs = info.getInetAddresses();
                if (addrs != null) {
                    for (InetAddress a : addrs) {
                        if (a == null || a.isLoopbackAddress()) continue;
                        if (hostAny == null) hostAny = a;
                        if (a instanceof Inet4Address) { host4 = a; break; }
                    }
                }
                InetAddress host = (host4 != null) ? host4 : hostAny;

                if (host == null) return;

                /*
                String key = info.getName() + "|" + info.getPort() + "|" + host.getHostAddress();
                Log.i(TAG, "< " + key);

                if (!seen.add(key)) {
                    // notify update (no TXT on dup to keep it lightweight)
                     if (cb != null) cb.onService(host, info.getPort(), null);
                    return;
                }
                */

                // === TXT in arrival order (raw RDATA parse) ===
                String[] txt = parseTxtInOrder(info);

                Log.i(TAG, "ok : name=" + info.getName()
                        + " host=" + host.getHostAddress()
                        + " port=" + info.getPort()
                        + " txt=" + java.util.Arrays.toString(txt));

                if (cb != null) cb.onService(host, info.getPort(), txt);
            } catch (Throwable t) {
                Log.w(TAG, "resolve fail", t);
            }
        }
    };

    // Parse ServiceInfo.getTextBytes() as a sequence of <len><bytes> strings, preserving order.
    private static String[] parseTxtInOrder(ServiceInfo info) {
        byte[] raw = info.getTextBytes();
        if (raw == null || raw.length == 0) return new String[0];

        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            i++;
            if (len == 0) {
                // zero-length string allowed; represent as empty
                out.add("");
                continue;
            }
            if (i + len > raw.length) {
                // malformed/truncated; stop parsing to avoid OOB
                break;
            }
            // TXT strings are opaque bytes; usually k[=v] in ASCII/UTF-8.
            String s = new String(raw, i, len, StandardCharsets.ISO_8859_1);
            out.add(s);
            i += len;
        }
        return out.toArray(new String[0]);
    }

    private void acquireMulticastLockSafe() {
        try {
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("mdns-lock");
                mlock.setReferenceCounted(true);
                mlock.acquire();
                Log.i(TAG, "MulticastLock acquired");
            }
        } catch (Throwable t) {
            Log.w(TAG, "MulticastLock acquire failed", t);
        }
    }

    private void releaseMulticastLockSafe() {
        try { if (mlock != null && mlock.isHeld()) mlock.release(); } catch (Throwable ignore) {}
        mlock = null;
    }

    private static InetAddress chooseWifiIPv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String ln = (nif.getName() != null) ? nif.getName().toLowerCase() : "";
                boolean isWifi = ln.startsWith("wlan") || ln.contains("wifi");
                if (!isWifi) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
            // fallback: any IPv4
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "chooseWifiIPv4 failed", t);
        }
        return null;
    }
}
