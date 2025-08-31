package org.example.mininative;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class StreamService extends Service implements Runnable {
  private static final String TAG = "MiniNativeStream";

  // intents / channel
  private static final String CH = "pwnet_stream_v1";
  private static final int NID = 1001;

  private static final String ACT_STATE="org.example.mininative.STATE";
  private static final String ACT_STOP="org.example.mininative.STOP";
  private static final String ACT_SET_GAIN="org.example.mininative.SET_GAIN";
  private static final String ACT_SET_MUTED="org.example.mininative.SET_MUTED";

  // prefs keys
  private static final String PREFS="mn_prefs";
  private static final String KEY_HOST="host", KEY_PORT="port", KEY_GAIN="gain", KEY_MUTED="muted";

  // status
  private volatile boolean running=false;
  private volatile boolean stopping=false;
  private volatile boolean muted=false;
  private volatile float gain=1.0f; // 0..1

  private String host;
  private int port;

  private Intent data;
  private int resultCode;
  private Thread th;

  @Override public int onStartCommand(Intent i, int flags, int id) {
    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

    if (i != null) {
      String act = i.getAction();
      if (ACT_STOP.equals(act)) {
        Log.i(TAG, "stop requested (user)");
        stopping = true;
        running = false;
        if (th != null) th.interrupt();
        // fall through to update notification/state
        notifyStatus("STOPPING");
        sendState("STOPPING", 0, 0, 0);
        return START_NOT_STICKY;
      }
      if (ACT_SET_GAIN.equals(act)) {
        float g = i.getFloatExtra("value", gain);
        if (g < 0f) g = 0f; if (g > 1f) g = 1f;
        gain = g;
        prefs.edit().putFloat(KEY_GAIN, gain).apply();
        Log.i(TAG, "gain=" + gain);
        sendState(running ? "CONNECTED" : (stopping ? "STOPPING" : "DISCONNECTED"), 0, 0, 0);
        updateNotif(running ? "CONNECTED" : (stopping ? "STOPPING" : "DISCONNECTED"));
        return START_STICKY;
      }
      if (ACT_SET_MUTED.equals(act)) {
        muted = i.getBooleanExtra("muted", false);
        prefs.edit().putBoolean(KEY_MUTED, muted).apply();
        Log.i(TAG, "muted=" + muted);
        sendState(running ? "CONNECTED" : (stopping ? "STOPPING" : "DISCONNECTED"), 0, 0, 0);
        updateNotif(running ? "CONNECTED" : (stopping ? "STOPPING" : "DISCONNECTED"));
        return START_STICKY;
      }
    }

    // normal start
    resultCode = (i!=null) ? i.getIntExtra("code", Activity.RESULT_CANCELED) : Activity.RESULT_CANCELED;
    data = (i!=null) ? i.getParcelableExtra("data") : null;
    if (resultCode != Activity.RESULT_OK || data == null) {
      Log.e(TAG, "start: projection data missing");
      stopSelf();
      return START_NOT_STICKY;
    }

    host = prefs.getString(KEY_HOST, Config.HOST);
    port = prefs.getInt(KEY_PORT, Config.PORT);
    gain = clamp01(prefs.getFloat(KEY_GAIN, 1.0f));
    muted = prefs.getBoolean(KEY_MUTED, false);

    ensureChannel();
    startForeground(NID, buildNotif("CONNECTING"));

    if (th == null || !running) {
      stopping = false;
      running = true;
      th = new Thread(this, "pwnet-stream");
      th.start();
    }
    return START_STICKY;
  }

  private void ensureChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CH, "PW-net streamer", NotificationManager.IMPORTANCE_LOW);
      ch.setDescription("PW-net audio streamer");
      ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }
  }

  private Notification buildNotif(String status) {
    PendingIntent openPI = PendingIntent.getActivity(
        this, 1, new Intent(this, MainActivity.class),
        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    PendingIntent stopPI = PendingIntent.getService(
        this, 2, new Intent(this, StreamService.class).setAction(ACT_STOP),
        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
        ? new Notification.Builder(this, CH)
        : new Notification.Builder(this);
    String line = host + ":" + port + " • " + status;
    return nb
        .setContentTitle("PW-net audio streamer")
        .setContentText(line)
        .setSmallIcon(R.drawable.ic_stat_pwnet)
        .setOnlyAlertOnce(true)
        .setOngoing("CONNECTED".equals(status) || "CONNECTING".equals(status))
        .setContentIntent(openPI)
        .addAction(android.R.drawable.ic_delete, "Stop", stopPI)
        .build();
  }

  private void updateNotif(String status) {
    NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    nm.notify(NID, buildNotif(status));
  }

  private void notifyStatus(String status) {
    updateNotif(status);
  }

  private void sendState(String status, long txBytes, int kbps, int attempts) {
    Intent s = new Intent(ACT_STATE)
        .putExtra("status", status)
        .putExtra("tx", txBytes)
        .putExtra("kbps", kbps)
        .putExtra("attempts", attempts)
        .putExtra("muted", muted)
        .putExtra("gain", gain);
    sendBroadcast(s);
  }

  @Override public IBinder onBind(Intent i) { return null; }

  @Override public void onDestroy() {
    running = false; stopping = true;
    if (th != null) th.interrupt();
    super.onDestroy();
  }

  @Override public void run() {
    final int SR=48000, CHN=2, BYTES=2;
    AudioRecord rec = null;
    MediaProjection mp = null;
    int attempts = 0;

    try {
      MediaProjectionManager mpm = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
      mp = mpm.getMediaProjection(resultCode, data);

      AudioPlaybackCaptureConfiguration cfg =
          new AudioPlaybackCaptureConfiguration.Builder(mp)
              .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
              .addMatchingUsage(AudioAttributes.USAGE_GAME)
              .build();

      AudioFormat fmt = new AudioFormat.Builder()
          .setSampleRate(SR)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
          .build();

      int frameBytes = CHN * BYTES;
      int chunkFrames = SR / 100; // 10 ms
      int bufBytes = chunkFrames * frameBytes;
      int minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      int recBuf = Math.max(bufBytes * 8, Math.max(minBuf, 4096));
      Log.i(TAG, "AudioRecord cfg sr=" + SR + " ch=" + CHN + " fmt=S16 minBuf=" + minBuf + " recBuf=" + recBuf + " chunk=" + bufBytes + "B");

      rec = new AudioRecord.Builder()
          .setAudioPlaybackCaptureConfig(cfg)
          .setAudioFormat(fmt)
          .setBufferSizeInBytes(recBuf)
          .build();
      rec.startRecording();

      byte[] buf = new byte[bufBytes];
      long t0 = SystemClock.elapsedRealtime();
      long bytesOut = 0;
      sendState("CONNECTING", 0, 0, attempts);
      notifyStatus("CONNECTING");

      outer:
      while (running && !stopping) {
        attempts++;
        sendState("CONNECTING", bytesOut, 0, attempts);
        notifyStatus("CONNECTING");
        if (!running || stopping) break;

        try (Socket s = new Socket()) {
          // resolve + connect with short timeout to be responsive to STOP
          InetAddress addr = InetAddress.getByName(host);
          s.connect(new InetSocketAddress(addr, port), 1500);
          s.setTcpNoDelay(true); s.setKeepAlive(true);
          OutputStream out = s.getOutputStream();
          Log.i(TAG, "connect ok peer=" + addr.getHostAddress() + ":" + port);

          sendState("CONNECTED", 0, 0, attempts);
          notifyStatus("CONNECTED");

          t0 = SystemClock.elapsedRealtime();
          bytesOut = 0;

          while (running && !stopping) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) break;

            if (muted) {
              for (int i=0;i<n;i++) buf[i]=0;
            } else if (gain != 1.0f) { // in-place S16 gain 0..1
              for (int i=0;i<n;i+=2) {
                int lo = buf[i] & 0xFF, hi = buf[i+1];
                int s16 = (hi<<8)|lo;
                int v = (int)Math.round(s16 * gain);
                if (v>32767) v=32767; else if (v<-32768) v=-32768;
                buf[i]=(byte)(v & 0xFF); buf[i+1]=(byte)((v>>>8)&0xFF);
              }
            }

            out.write(buf, 0, n);
            bytesOut += n;

            long dt = SystemClock.elapsedRealtime() - t0;
            if (dt >= 2000) {
              int kbps = (int)((bytesOut * 8L) / dt);
              Log.i(TAG, "tx ~" + kbps + " kb/s (" + bytesOut + "B/" + dt + "ms) gain=" + gain + " muted=" + muted);
              sendState("CONNECTED", bytesOut, kbps, attempts);
              t0 = SystemClock.elapsedRealtime(); bytesOut = 0;
            }

            if (!running || stopping) break;
          }

          Log.i(TAG, "socket closed");
        } catch (Exception e) {
          if (!running || stopping) break;
          Log.w(TAG, "connect error: " + e.getMessage());
          try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
          continue;
        }
      }

      Log.i(TAG, "stream loop exit; running=" + running + " stopping=" + stopping);

    } catch (Throwable t) {
      Log.e(TAG, "fatal", t);
    } finally {
      try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignore) {}
      try { if (data != null) { MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection mp2 = mpm.getMediaProjection(resultCode, data); if (mp2!=null) mp2.stop(); } } catch(Throwable ignore){}
      sendState("DISCONNECTED", 0, 0, 0);
      notifyStatus("DISCONNECTED");
      stopForeground(true);
      stopSelf();
      running = false; stopping = false;
    }
  }

  private static float clamp01(float f){ if (f<0f) return 0f; if (f>1f) return 1f; return f; }
}
