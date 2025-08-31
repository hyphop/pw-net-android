package org.example.mininative;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;

public class CaptureActivity extends Activity {
  private static final int REQ_PROJ=2001, REQ_MIC=2002, REQ_POST=2003;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    // usually not launched directly; kept for fallback
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_POST);
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_MIC);
      return;
    }
    MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
  }

  @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
    super.onRequestPermissionsResult(rc, p, r);
    if (rc==REQ_MIC && r.length>0 && r[0]==PackageManager.PERMISSION_GRANTED) {
      MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
      startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    } else {
      finish();
    }
  }

  @Override protected void onActivityResult(int rc, int res, Intent data) {
    super.onActivityResult(rc, res, data);
    if (rc!=REQ_PROJ || res!=RESULT_OK || data==null) { finish(); return; }
    Intent svc = new Intent(this, StreamService.class)
        .putExtra("code", res).putExtra("data", data);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
    finish();
  }
}
