package org.example.mininative;

import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.provider.Settings;
import android.content.ComponentName;

import android.media.session.MediaSessionManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class MediaWatchService extends Service {
  private static final String TAG = "MediaWatchService";

  // Broadcast you can subscribe to from MainActivity
  public static final String ACT_MEDIA_LIST   = "org.example.mininative.ACT_MEDIA_LIST";
  public static final String EXTRA_PKGS       = "pkgs";       // String[]
  public static final String EXTRA_UIDS       = "uids";       // int[]
  public static final String EXTRA_NLS        = "nls_enabled";// boolean
  public static final String EXTRA_TS         = "ts";         // long (System.currentTimeMillis)

  private MediaSessionManager msm;
  private final Handler ui = new Handler(Looper.getMainLooper());

  private final MediaSessionManager.OnActiveSessionsChangedListener listener =
      new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override public void onActiveSessionsChanged(List<MediaController> ctrls) {
          broadcastSnapshot(ctrls);
        }
      };

  @Override public void onCreate() {
    super.onCreate();
    msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
    // Try initial register; if not enabled, just post a negative state once.
    if (isNLSEnabled()) {
      try {
        msm.addOnActiveSessionsChangedListener(listener,
            new ComponentName(this, NLService.class), ui);
        // initial fill
        broadcastSnapshot(msm.getActiveSessions(new ComponentName(this, NLService.class)));
      } catch (SecurityException se) {
        Log.w(TAG, "NLS seems disabled; posting empty snapshot");
        broadcastNoAccess();
      }
    } else {
      Log.i(TAG, "NLS disabled at service start; posting empty snapshot");
      broadcastNoAccess();
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    // Optional manual refresh trigger
    if (intent != null && "REFRESH".equals(intent.getAction())) {
      if (isNLSEnabled()) {
        try {
          broadcastSnapshot(msm.getActiveSessions(new ComponentName(this, NLService.class)));
        } catch (Throwable t) { Log.w(TAG, "refresh failed", t); }
      } else broadcastNoAccess();
    }
    return START_STICKY;
  }

  @Override public void onDestroy() {
    super.onDestroy();
    try { if (msm != null) msm.removeOnActiveSessionsChangedListener(listener); } catch (Throwable ignore) {}
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  private boolean isNLSEnabled() {
    String flat = Settings.Secure.getString(getContentResolver(),
        "enabled_notification_listeners");
    ComponentName cn = new ComponentName(this, NLService.class);
    return flat != null && flat.contains(cn.flattenToString());
  }

  private static Integer resolveUidForPackage(Context ctx, String pkg) {
    try {
      if (android.os.Build.VERSION.SDK_INT >= 33) {
        ApplicationInfo ai = ctx.getPackageManager()
            .getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
        return ai.uid;
      } else {
        ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
        return ai.uid;
      }
    } catch (PackageManager.NameNotFoundException e) {
      return null;
    }
  }

  private void broadcastNoAccess() {
    Intent i = new Intent(ACT_MEDIA_LIST);
    i.putExtra(EXTRA_NLS, false);
    i.putExtra(EXTRA_PKGS, new String[0]);
    i.putExtra(EXTRA_UIDS, new int[0]);
    i.putExtra(EXTRA_TS, System.currentTimeMillis());
    sendBroadcast(i);
  }

  private void broadcastSnapshot(List<MediaController> ctrls) {
    if (ctrls == null) { broadcastNoAccess(); return; }

    // Keep order stable; one entry per package; only PLAYING
    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
    for (MediaController mc : ctrls) {
      PlaybackState st = mc.getPlaybackState();
      if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
        String pkg = mc.getPackageName();
        if (!map.containsKey(pkg)) {
          Integer uid = resolveUidForPackage(this, pkg);
          map.put(pkg, (uid != null ? uid : -1));
        }
      }
    }

    ArrayList<String> pkgs = new ArrayList<>(map.keySet());
    int[] uids = new int[pkgs.size()];
    for (int i = 0; i < pkgs.size(); i++) uids[i] = map.get(pkgs.get(i));

    Intent i = new Intent(ACT_MEDIA_LIST);
    i.putExtra(EXTRA_NLS, true);
    i.putExtra(EXTRA_PKGS, pkgs.toArray(new String[0]));
    i.putExtra(EXTRA_UIDS, uids);
    i.putExtra(EXTRA_TS, System.currentTimeMillis());
    sendBroadcast(i);

    Log.i(TAG, "posted media list n=" + pkgs.size());
  }
}
