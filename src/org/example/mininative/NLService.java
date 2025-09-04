// src/org/example/mininative/NLService.java
package org.example.mininative;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Super-simple Notification Listener that:
 *  1) Logs lifecycle + media session events.
 *  2) Maintains a state hash keyed by uid (fallback: pkg.hashCode()) with {pkg, uid, state, time, title}.
 *  3) Exposes the live map to the main UI via a bound service (no JSON, no broadcasts).
 */
public class NLService extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

  private static final String TAG = "pw-nlsSrv";
  private static final String TAG2 = "pw-nls";
  private static final String SYS_ACTION = NotificationListenerService.SERVICE_INTERFACE;
  // "android.service.notification.NotificationListenerService"

  // single logging helper
  private static void logi(String msg) {
      Log.i(TAG2, msg);
  }

  public static final class PlayerInfo {
    public String pkg;
    public int uid;
    public int state;     // PlaybackState.STATE_*
    public long timeMs;   // System.currentTimeMillis()
    public String title;
  }

  // Live state hash: key = uid if >0 else pkg.hashCode()
  private static final ConcurrentMap<Integer, PlayerInfo> STATE = new ConcurrentHashMap<>();

  private final IBinder binder = new LocalBinder();
  public final class LocalBinder extends Binder {
    public NLService getService() { return NLService.this; }
  }

  private MediaSessionManager msm;

  // ===== Lifecycle / binding =====
  @Override public void onCreate() {
    super.onCreate();
    logi("onCreate");
  }

  @Override public IBinder onBind(android.content.Intent intent) {
    logi("onBind");
    // If the system is binding us as a notification-listener, defer to the framework.
    if (intent != null && SYS_ACTION.equals(intent.getAction())) {
      return super.onBind(intent);
    }
    // Otherwise, it's our app binding to fetch the map.
    return binder;
  }

  @Override public boolean onUnbind(android.content.Intent intent) {
    logi("onUnbind");
    return super.onUnbind(intent);
  }

  @Override public void onDestroy() {
    logi("onDestroy");
    if (msm != null) {
      try { msm.removeOnActiveSessionsChangedListener(this); } catch (Throwable ignore) {}
    }
    super.onDestroy();
  }

  // ===== NotificationListenerService =====
  @Override public void onListenerConnected() {
    logi("onListenerConnected");
    msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
    ComponentName me = new ComponentName(this, NLService.class);
    if (msm != null) {
      msm.addOnActiveSessionsChangedListener(this, me);
      List<MediaController> init = msm.getActiveSessions(me);
      onActiveSessionsChanged(init);
    }
  }

  @Override public void onListenerDisconnected() {
    logi("onListenerDisconnected");
    if (msm != null) {
      try { msm.removeOnActiveSessionsChangedListener(this); } catch (Throwable ignore) {}
    }
  }

  // Optional: log notifications (not used for state)
  @Override public void onNotificationPosted(StatusBarNotification sbn) {
    logi("onNotificationPosted: " + sbn.getPackageName());
  }
  @Override public void onNotificationRemoved(StatusBarNotification sbn) {
    logi("onNotificationRemoved: " + sbn.getPackageName());
  }

  // ===== Media sessions tracking =====
  @Override
  public void onActiveSessionsChanged(List<MediaController> controllers) {
    int n = (controllers == null) ? 0 : controllers.size();
    logi("onActiveSessionsChanged n=" + n);
    if (controllers == null) return;

    for (MediaController mc : controllers) {
      if (mc == null) continue;
      final String pkg = mc.getPackageName();
      final int uid = resolveUid(pkg);
      // Initial snapshot
      updateFromController(mc, uid, pkg);

      // Listen for future changes
      try {
        mc.registerCallback(new MediaController.Callback() {
          @Override public void onPlaybackStateChanged(PlaybackState stateObj) {
            updateFromController(mc, uid, pkg);
          }
          @Override public void onMetadataChanged(MediaMetadata metadata) {
            updateFromController(mc, uid, pkg);
          }
          @Override public void onSessionDestroyed() {
            updateState(uid, pkg, PlaybackState.STATE_STOPPED, now(), currentTitle(mc));
          }
        });
      } catch (Throwable t) {
        logi("WARN registerCallback failed for " + pkg + " : " + t);
      }
    }
  }

  private void updateFromController(MediaController mc, int uid, String pkg) {
    int st = PlaybackState.STATE_NONE;
    try {
      PlaybackState ps = mc.getPlaybackState();
      if (ps != null) st = ps.getState();
    } catch (Throwable ignore) {}
    String title = currentTitle(mc);
    updateState(uid, pkg, st, now(), title);
  }

  private String currentTitle(MediaController mc) {
    try {
      MediaMetadata md = mc.getMetadata();
      if (md != null) {
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t != null) return t;
      }
    } catch (Throwable ignore) {}
    return null;
  }

  private void updateState(int uid, String pkg, int st, long time, String title) {
    if (pkg == null && uid <= 0) return;
    int key = (uid > 0) ? uid : pkg.hashCode();

    PlayerInfo pi = STATE.get(key);
    if (pi == null) {
      pi = new PlayerInfo();
      pi.pkg = pkg;
      pi.uid = uid;
      STATE.put(key, pi);
    }
    pi.state = st;
    pi.timeMs = time;
    pi.title = title;

    logi("state pkg=" + pkg + " uid=" + uid + " st=" + st + " time=" + time + " title=" + title);
  }

  private int resolveUid(String pkg) {
    if (pkg == null) return -1;
    try {
      ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
      return ai.uid;
    } catch (PackageManager.NameNotFoundException e) {
      return -1;
    }
  }

  private long now() { return System.currentTimeMillis(); }

  // ===== Public API for main UI =====
  /** Returns the live state map. Same-process only; do not mutate entries recklessly. */
  public ConcurrentMap<Integer, PlayerInfo> getStateMap() {
    return STATE;
  }

  /** Convenience dump for quick logging from the UI if needed. */
  public void logStateSnapshot(String tag) {
    for (Map.Entry<Integer, PlayerInfo> e : STATE.entrySet()) {
      PlayerInfo p = e.getValue();
      Log.i(tag, "key=" + e.getKey() + " pkg=" + p.pkg + " uid=" + p.uid +
          " st=" + p.state + " time=" + p.timeMs + " title=" + p.title);
    }
  }
}
