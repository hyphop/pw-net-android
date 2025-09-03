package org.example.mininative;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class NLService extends NotificationListenerService {

  // === public intents (UI <-> service) ===
  public static final String ACT_ASK_SOURCES = "org.example.mininative.ASK_SOURCES";
  public static final String ACT_SOURCES     = "org.example.mininative.SOURCES";

  private static final String TAG = "MediaWatch";

  // session snapshot (pkg -> info)
  private final Map<String, SourceInfo> sources = new HashMap<String, SourceInfo>();

  // track attached controllers + callbacks + last states (to catch PLAYING/PAUSED changes)
  private final Map<String, MediaController> attached = new HashMap<String, MediaController>();
  private final Map<String, MediaController.Callback> callbacks = new HashMap<String, MediaController.Callback>();
  private final Map<String, Integer> lastStates = new HashMap<String, Integer>();

  private MediaSessionManager msm;
  private Handler ui;
  private BroadcastReceiver askRx;
  private boolean sessionsCbRegistered = false;

  private static void logf(String fmt, Object... args) {
    Log.i(TAG,
        String.format(Locale.US, "[%6d] ", SystemClock.elapsedRealtime() % 1_000_000)
            + String.format(Locale.US, fmt, args));
  }

  // ---------- Lifecycle ----------
  @Override public void onCreate() {
    super.onCreate();
    ui  = new Handler(Looper.getMainLooper());
    msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);

    // Listen for one-shot UI queries
    askRx = new BroadcastReceiver() {
      @Override public void onReceive(Context ctx, Intent i) {
        if (ACT_ASK_SOURCES.equals(i.getAction())) {
          logf("ASK_SOURCES received");
          replySources(); // answer immediately with current snapshot
        }
      }
    };
    registerReceiver(askRx, new IntentFilter(ACT_ASK_SOURCES));

    logf("onCreate");
    // take initial snapshot (will work only if user granted Notification Access)
    safeSnapshotActiveSessions();
    // and start watching changes
    safeRegisterSessionsListener();
  }

  @Override public void onDestroy() {
    try { unregisterReceiver(askRx); } catch (Throwable ignore) {}
    try {
      if (msm != null && sessionsCbRegistered) {
        msm.removeOnActiveSessionsChangedListener(sessionsCb);
      }
    } catch (Throwable ignore) {}

    // Detach all controller callbacks
    try {
      for (String pkg : new ArrayList<String>(attached.keySet())) {
        MediaController mc = attached.get(pkg);
        MediaController.Callback cb = callbacks.get(pkg);
        if (mc != null && cb != null) {
          try { mc.unregisterCallback(cb); } catch (Throwable ignore) {}
        }
      }
      attached.clear();
      callbacks.clear();
      lastStates.clear();
    } catch (Throwable ignore) {}

    logf("onDestroy");
    super.onDestroy();
  }

  // ---------- Notification Listener callbacks ----------
  @Override public void onListenerConnected() {
    super.onListenerConnected();
    logf("onListenerConnected: NLS permission active");
    safeSnapshotActiveSessions();
  }

  @Override public void onListenerDisconnected() {
    super.onListenerDisconnected();
    logf("onListenerDisconnected: NLS permission lost?");
  }

  @Override public void onNotificationPosted(StatusBarNotification sbn) {
    // Keep for debugging; reads title via extras (you can trim if too noisy)
    try {
      String pkg = sbn.getPackageName();
      int uid = safeUidForPackage(pkg);
      String ch = (Build.VERSION.SDK_INT >= 26 && sbn.getNotification() != null)
                  ? sbn.getNotification().getChannelId() : "n/a";
      CharSequence title = (sbn.getNotification() != null
                            && sbn.getNotification().extras != null)
                            ? sbn.getNotification().extras.getCharSequence("android.title")
                            : null;
      logf("notif POST pkg=%s uid=%d id=%d ch=%s title=%s",
          pkg, uid, sbn.getId(), ch, String.valueOf(title));
    } catch (Throwable t) {
      logf("notif POST (error) %s", t.getMessage());
    }
  }

  @Override public void onNotificationRemoved(StatusBarNotification sbn) {
    try {
      String pkg = sbn.getPackageName();
      int uid = safeUidForPackage(pkg);
      logf("notif REMOVE pkg=%s uid=%d id=%d", pkg, uid, sbn.getId());
    } catch (Throwable t) {
      logf("notif REMOVE (error) %s", t.getMessage());
    }
  }

  @Override public void onNotificationRemoved(StatusBarNotification sbn,
                                              RankingMap rankingMap, int reason) {
    onNotificationRemoved(sbn); // keep one log format
  }

  // ---------- Media sessions (who is playing) ----------
  private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb =
      new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> ctrls) {
          logf("sessions changed size=%s", (ctrls != null ? String.valueOf(ctrls.size()) : "null"));
          rebuildSources(ctrls);
        }
      };

  private void safeRegisterSessionsListener() {
    try {
      if (msm != null && !sessionsCbRegistered) {
        msm.addOnActiveSessionsChangedListener(
            sessionsCb, new ComponentName(this, NLService.class), ui);
        sessionsCbRegistered = true;
        logf("registered sessionsCb");
      }
    } catch (SecurityException se) {
      logf("sessions listener NOT registered (grant Notification access in Settings)");
    } catch (Throwable t) {
      logf("sessions listener register error: %s", t.getMessage());
    }
  }

  private void safeSnapshotActiveSessions() {
    try {
      List<MediaController> ctrls =
          (msm != null) ? msm.getActiveSessions(new ComponentName(this, NLService.class)) : null;
      rebuildSources(ctrls);
    } catch (SecurityException se) {
      logf("snapshot: no permission (enable Notification access)");
    } catch (Throwable t) {
      logf("snapshot error: %s", t.getMessage());
    }
  }

  // Attach callbacks to controllers, update SourceInfo on every state change (incl. PAUSED).
  private void rebuildSources(List<MediaController> ctrls) {
    // Build set of present packages
    Set<String> present = new HashSet<String>();
    if (ctrls != null) {
      for (MediaController mc : ctrls) {
        final String pkg = mc.getPackageName();
        present.add(pkg);

        // Attach callback once per package
        if (!attached.containsKey(pkg)) {
          MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState st) {
              int s = (st != null ? st.getState() : PlaybackState.STATE_NONE);

              // Update snapshot
              SourceInfo si = sources.get(pkg);
              if (si == null) si = new SourceInfo();
              si.pkg   = pkg;
              si.uid   = safeUidForPackage(pkg);
              si.label = safeAppLabel(pkg);
              si.state = s;
              si.tMs   = SystemClock.elapsedRealtime();
              sources.put(pkg, si);

              // Log only on change
              Integer prevI = lastStates.get(pkg);
              int prev = (prevI != null ? prevI.intValue() : PlaybackState.STATE_NONE);
              if (prev != s) {
                logf("state: %s uid=%d %s -> %s", pkg, si.uid, stateName(prev), stateName(s));
                lastStates.put(pkg, Integer.valueOf(s));
              }
            }
          };
          try { mc.registerCallback(cb, ui); } catch (Throwable ignore) {}
          attached.put(pkg, mc);
          callbacks.put(pkg, cb);

          // Initial snapshot/log without waiting for callback
          PlaybackState st = null;
          try { st = mc.getPlaybackState(); } catch (Throwable ignore) {}
          int s0 = (st != null ? st.getState() : PlaybackState.STATE_NONE);

          SourceInfo si0 = sources.get(pkg);
          if (si0 == null) si0 = new SourceInfo();
          si0.pkg   = pkg;
          si0.uid   = safeUidForPackage(pkg);
          si0.label = safeAppLabel(pkg);
          si0.state = s0;
          si0.tMs   = SystemClock.elapsedRealtime();
          sources.put(pkg, si0);

          Integer prevI = lastStates.get(pkg);
          int prev = (prevI != null ? prevI.intValue() : PlaybackState.STATE_NONE);
          if (prev != s0) {
            logf("initial: %s uid=%d %s -> %s", pkg, si0.uid, stateName(prev), stateName(s0));
            lastStates.put(pkg, Integer.valueOf(s0));
          } else {
            logf("initial: %s already %s", pkg, stateName(s0));
          }
        } else {
          // Already attached; refresh current state into snapshot
          PlaybackState st = null;
          try { st = mc.getPlaybackState(); } catch (Throwable ignore) {}
          int s = (st != null ? st.getState() : PlaybackState.STATE_NONE);

          SourceInfo si = sources.get(pkg);
          if (si == null) si = new SourceInfo();
          si.pkg   = pkg;
          si.uid   = safeUidForPackage(pkg);
          si.label = safeAppLabel(pkg);
          si.state = s;
          si.tMs   = SystemClock.elapsedRealtime();
          sources.put(pkg, si);

          Integer prevI = lastStates.get(pkg);
          int prev = (prevI != null ? prevI.intValue() : PlaybackState.STATE_NONE);
          if (prev != s) {
            logf("state(sync): %s uid=%d %s -> %s", pkg, si.uid, stateName(prev), stateName(s));
            lastStates.put(pkg, Integer.valueOf(s));
          }
        }
      }
    }

    // Detach callbacks for controllers that disappeared
    Set<String> gone = new HashSet<String>(attached.keySet());
    gone.removeAll(present);
    for (String pkg : gone) {
      try {
        MediaController mc = attached.get(pkg);
        MediaController.Callback cb = callbacks.get(pkg);
        if (mc != null && cb != null) {
          try { mc.unregisterCallback(cb); } catch (Throwable ignore) {}
        }
      } catch (Throwable ignore) {}
      attached.remove(pkg);
      callbacks.remove(pkg);

      SourceInfo old = sources.remove(pkg);
      Integer prevI = lastStates.remove(pkg);
      int prev = (prevI != null ? prevI.intValue() : PlaybackState.STATE_NONE);
      if (old != null) logf("source gone: %s (was %s)", pkg, stateName(prev));
    }
  }

  // ---------- Respond to UI with current snapshot ----------
  private void replySources() {
    ArrayList<String> pkgs   = new ArrayList<String>();
    ArrayList<Integer> uids  = new ArrayList<Integer>();
    ArrayList<String> labels = new ArrayList<String>();
    ArrayList<Integer> states= new ArrayList<Integer>();

    for (SourceInfo si : sources.values()) {
      pkgs.add(si.pkg);
      uids.add(Integer.valueOf(si.uid));
      labels.add(si.label);
      states.add(Integer.valueOf(si.state));
    }

    Intent out = new Intent(ACT_SOURCES)
        .putStringArrayListExtra("pkgs", pkgs)
        .putIntegerArrayListExtra("uids", uids)
        .putStringArrayListExtra("labels", labels)
        .putIntegerArrayListExtra("states", states)
        .putExtra("ts", SystemClock.elapsedRealtime());
    out.setPackage(getPackageName()); // in-app only
    sendBroadcast(out);

    logf("SOURCES sent: %d items", pkgs.size());
  }

  // ---------- Helpers ----------
  private int safeUidForPackage(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      if (Build.VERSION.SDK_INT >= 33) {
        ApplicationInfo ai = pm.getApplicationInfo(pkg,
            PackageManager.ApplicationInfoFlags.of(0));
        return ai.uid;
      } else {
        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
        return ai.uid;
      }
    } catch (Throwable t) {
      return -1;
    }
  }

  private String safeAppLabel(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      if (Build.VERSION.SDK_INT >= 33) {
        return String.valueOf(pm.getApplicationLabel(
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))));
      } else {
        return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
      }
    } catch (Throwable t) {
      return pkg;
    }
  }

  private static String stateName(int s) {
    switch (s) {
      case PlaybackState.STATE_PLAYING:   return "PLAYING";
      case PlaybackState.STATE_BUFFERING: return "BUFFERING";
      case PlaybackState.STATE_PAUSED:    return "PAUSED";
      case PlaybackState.STATE_STOPPED:   return "STOPPED";
      case PlaybackState.STATE_NONE:
      default: return "NONE(" + s + ")";
    }
  }

  private static final class SourceInfo {
    String pkg;
    int    uid;
    String label;
    int    state;
    long   tMs;
  }
}
