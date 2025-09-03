package org.example.mininative;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.content.res.Configuration;

import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.media.session.PlaybackState.STATE_NONE;
import static android.media.session.PlaybackState.STATE_PLAYING;
import static android.media.session.PlaybackState.STATE_BUFFERING;
import static android.media.session.PlaybackState.STATE_PAUSED;
import static android.media.session.PlaybackState.STATE_STOPPED;

public class MediaWatchService extends Service {
  private static final String TAG = "MediaWatch";
  private static final String CH_ID = "mediawatch_logger";

  private final Handler ui = new Handler(Looper.getMainLooper());
  private MediaSessionManager msm;

  // one callback per package so we know who fired
  private final Map<String, MediaController> attached = new HashMap<>();
  private final Map<String, MediaController.Callback> callbacks = new HashMap<>();
  private final Map<String, Integer> lastStates = new HashMap<>();

  private static void logf(String fmt, Object... args) {
    Log.i(TAG,
        String.format(Locale.US, "[%6d] ", SystemClock.elapsedRealtime() % 1_000_000)
            + String.format(Locale.US, fmt, args));
  }

  private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb =
      new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> ctrls) {
          logf("sessionsCb: activeSessionsChanged size=%d", (ctrls != null ? ctrls.size() : -1));
          reattachCallbacks(ctrls);
        }
      };

  @Override public void onCreate() {
    super.onCreate();
    logf("onCreate");

    // Uncomment to keep service foreground (recommended on O+ if you want it long-lived)
    startAsForeground();

    msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);

    if (!isNLSEnabled()) {
      logf("NLS NOT enabled (open settings to enable)");
      return;
    }

    try {
      msm.addOnActiveSessionsChangedListener(
          sessionsCb, new ComponentName(this, NLService.class), ui);
      logf("registered sessionsCb");
      // initial attach + snapshot log
      reattachCallbacks(msm.getActiveSessions(new ComponentName(this, NLService.class)));
    } catch (SecurityException se) {
      logf("No permission for MSM (enable notification access)");
    }
  }

  @Override public int onStartCommand(Intent i, int flags, int startId) {
    logf("onStartCommand action=%s flags=%d startId=%d",
        (i != null ? i.getAction() : "<null>"), flags, startId);
    // nothing special; keep running
    return START_STICKY;
  }

  @Override public void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    logf("onTaskRemoved rootIntent=%s", String.valueOf(rootIntent));
    // stopSelf(); // optional
  }

  @Override public void onDestroy() {
    logf("onDestroy begin");
    try {
      if (msm != null) {
        msm.removeOnActiveSessionsChangedListener(sessionsCb);
        logf("unregistered sessionsCb");
      }
    } catch (Throwable ignore) {}

    for (String pkg : new ArrayList<>(attached.keySet())) {
      try {
        MediaController mc = attached.get(pkg);
        MediaController.Callback cb = callbacks.get(pkg);
        if (mc != null && cb != null) {
          mc.unregisterCallback(cb);
          logf("unregister cb pkg=%s", pkg);
        }
      } catch (Throwable ignore) {}
    }
    attached.clear();
    callbacks.clear();

    stopForeground(true); // if you used startAsForeground()
    logf("onDestroy end");
    super.onDestroy();
  }

  @Override public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    logf("onConfigurationChanged %s", newConfig.toString());
  }

  @Override public void onLowMemory() {
    super.onLowMemory();
    logf("onLowMemory");
  }

  @Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    logf("onTrimMemory level=%d", level);
  }

  @Override public boolean onUnbind(Intent intent) {
    logf("onUnbind intent=%s", String.valueOf(intent));
    return super.onUnbind(intent);
  }

  @Override public void onRebind(Intent intent) {
    super.onRebind(intent);
    logf("onRebind intent=%s", String.valueOf(intent));
  }

  @Override public IBinder onBind(Intent intent) {
  logf("onBind requested intent=%s", String.valueOf(intent));
  return null; // still a started-only service (no binding)
  }

  // ===== internals =====

  private void startAsForeground() {
    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CH_ID, "Media Watch Logger",
          NotificationManager.IMPORTANCE_MIN);
      nm.createNotificationChannel(ch);
      Notification n = new Notification.Builder(this, CH_ID)
          .setSmallIcon(android.R.drawable.ic_media_play)
          .setContentTitle("Media session logger running")
          .build();
      startForeground(1, n);
    } else {
      Notification n = new Notification.Builder(this)
          .setSmallIcon(android.R.drawable.ic_media_play)
          .setContentTitle("Media session logger running")
          .build();
      startForeground(1, n);
    }
  }

  private boolean isNLSEnabled() {
    String flat = Settings.Secure.getString(getContentResolver(),
        "enabled_notification_listeners");
    return flat != null &&
           flat.contains(new ComponentName(this, NLService.class).flattenToString());
  }

  private static String stateName(int s) {
    if (s == STATE_PLAYING)   return "PLAYING";
    if (s == STATE_BUFFERING) return "BUFFERING";
    if (s == STATE_PAUSED)    return "PAUSED";
    if (s == STATE_STOPPED)   return "STOPPED";
    return "NONE(" + s + ")";
  }

  private int resolveUid(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      ApplicationInfo ai;
      if (Build.VERSION.SDK_INT >= 33) {
        ai = pm.getApplicationInfo(pkg,
            PackageManager.ApplicationInfoFlags.of(0));
      } else {
        ai = pm.getApplicationInfo(pkg, 0);
      }
      return ai.uid;
    } catch (PackageManager.NameNotFoundException e) {
      return -1; // package not visible (add <queries> or use QUERY_ALL_PACKAGES for dev)
    }
  }

  // Attach callbacks to current controllers; log initial states; handle PAUSE reliably.
  private void reattachCallbacks(List<MediaController> ctrls) {
    if (ctrls == null) { logf("reattachCallbacks: ctrls=null"); return; }
    logf("reattachCallbacks: ctrls=%d", ctrls.size());

    // Build new set of packages present
    Set<String> present = new HashSet<>();
    for (MediaController mc : ctrls) {
      present.add(mc.getPackageName());
    }

    // Detach removed
    for (String pkg : new ArrayList<>(attached.keySet())) {
      if (!present.contains(pkg)) {
        try {
          MediaController mc = attached.get(pkg);
          MediaController.Callback cb = callbacks.get(pkg);
          if (mc != null && cb != null) mc.unregisterCallback(cb);
        } catch (Throwable ignore) {}
        attached.remove(pkg);
        callbacks.remove(pkg);
        // Treat disappearance as NONE (optional; handy for logs)
        Integer oldS = lastStates.containsKey(pkg) ? lastStates.get(pkg) : STATE_NONE;
        if (oldS != STATE_NONE) {
          int uid = resolveUid(pkg);
          logf("detach: %s uid=%d %s -> %s", pkg, uid, stateName(oldS), stateName(STATE_NONE));
          lastStates.put(pkg, STATE_NONE);
        } else {
          logf("detach: %s (no previous state)", pkg);
        }
      }
    }

    // Attach new + log their current state
    for (MediaController mc : ctrls) {
      final String pkg = mc.getPackageName();
      if (!attached.containsKey(pkg)) {
        logf("attach: %s", pkg);
        MediaController.Callback cb = new MediaController.Callback() {
          @Override public void onPlaybackStateChanged(PlaybackState st) {
            int s = (st != null) ? st.getState() : STATE_NONE;
            Integer prev = (lastStates.containsKey(pkg) ? lastStates.get(pkg) : STATE_NONE);
            if (prev == null) prev = STATE_NONE;
            if (!prev.equals(s)) {
              int uid = resolveUid(pkg);
              logf("state: %s uid=%d %s -> %s", pkg, uid, stateName(prev), stateName(s));
              lastStates.put(pkg, s);
            }
          }
        };
        try { mc.registerCallback(cb, ui); } catch (Throwable ignore) {}
        attached.put(pkg, mc);
        callbacks.put(pkg, cb);

        // Log initial snapshot for this controller
        PlaybackState st = mc.getPlaybackState();
        int s = (st != null) ? st.getState() : STATE_NONE;
        Integer prev = (lastStates.containsKey(pkg) ? lastStates.get(pkg) : STATE_NONE);
        if (prev == null) prev = STATE_NONE;
        if (!prev.equals(s)) {
          int uid = resolveUid(pkg);
          logf("initial: %s uid=%d %s -> %s", pkg, uid, stateName(prev), stateName(s));
          lastStates.put(pkg, s);
        } else {
          logf("initial: %s already %s", pkg, stateName(s));
        }
      }
    }
  }
}
