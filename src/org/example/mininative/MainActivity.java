package org.example.mininative;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;

public class MainActivity extends Activity {
  // intents / prefs keys (must match StreamService)
  private static final String PREFS="mn_prefs";
  private static final String KEY_HOST="host", KEY_PORT="port", KEY_GAIN="gain", KEY_MUTED="muted";
  private static final String ACT_STATE="org.example.mininative.STATE";
  private static final String ACT_STOP="org.example.mininative.STOP";
  private static final String ACT_SET_GAIN="org.example.mininative.SET_GAIN";
  private static final String ACT_SET_MUTED="org.example.mininative.SET_MUTED";

  private static final int REQ_MIC=1001, REQ_PROJ=1002, REQ_POST=1003;

  // colors (match colors.xml)
  private static final int CYAN  = 0xFF00FFFF;
  private static final int CYAN_DIM = 0xFF55CFCF;
  private static final int BG    = 0xFF000000;
  private static final int BLUE  = 0xFF0A84FF;
  private static final int YEL   = 0xFFFFD60A;
  private static final int WHITE = 0xFFFFFFFF;

  private EditText hostEt, portEt;
  private SeekBar gainSb;
  private TextView gainTv, topTv, botTv;
  private Button stateBtn, muteBtn, applyBtn, exitBtn;

  private SharedPreferences prefs;
  private String status = "DISCONNECTED";
  private boolean muted = false;

  // receiver from service
  private final BroadcastReceiver br = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if (!ACT_STATE.equals(i.getAction())) return;
      status = i.getStringExtra("status");
      long tx = i.getLongExtra("tx", 0L);
      int kbps = i.getIntExtra("kbps", 0);
      int attempts = i.getIntExtra("attempts", 0);
      muted = i.getBooleanExtra("muted", false);
      float g = i.getFloatExtra("gain", prefs.getFloat(KEY_GAIN, 1f));
      // reflect on UI
      setStateButtonFor(status);
      topTv.setText("TX " + tx + " B · " + kbps + " kb/s · attempts " + attempts);
      botTv.setText(status);
      // keep gain % label right-aligned text
      gainTv.setText("gain " + Math.round(g * 100f) + "%");
      muteBtn.setText(muted ? "Unmute" : "Mute");
    }
  };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

    // ===== Layout root =====
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(14);
    root.setPadding(pad, pad, pad, pad);
    root.setGravity(Gravity.TOP|Gravity.START);
    root.setBackgroundColor(BG);

    // --- Host field (bordered) ---
    hostEt = new EditText(this);
    hostEt.setHint("host");
    hostEt.setSingleLine(true);
    hostEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    hostEt.setText(prefs.getString(KEY_HOST, Config.HOST));
    styleEdit(hostEt);
    root.addView(hostEt);

    // --- Port field (bordered) ---
    portEt = new EditText(this);
    portEt.setHint("port");
    portEt.setSingleLine(true);
    portEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    portEt.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(5) });
    portEt.setText(String.valueOf(prefs.getInt(KEY_PORT, Config.PORT)));
    styleEdit(portEt);
    root.addView(portEt);

    // --- Gain label (right aligned) + SeekBar full width below ---
    LinearLayout gainHead = new LinearLayout(this);
    gainHead.setOrientation(LinearLayout.HORIZONTAL);
    gainHead.setPadding(0, dp(8), 0, dp(4));
    TextView spacer = new TextView(this);
    SpacerParams(spacer);
    gainHead.addView(spacer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    gainTv = t("");
    gainTv.setTextColor(CYAN);
    gainTv.setGravity(Gravity.END);
    gainHead.addView(gainTv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    root.addView(gainHead);

    gainSb = new SeekBar(this);
    gainSb.setMax(100);
    float g0 = clamp01(prefs.getFloat(KEY_GAIN, 1.0f));
    gainSb.setProgress(Math.round(g0*100));
    gainTv.setText("gain " + Math.round(g0*100) + "%");
    styleSeek(gainSb);
    root.addView(gainSb);

    // --- Buttons row: START/WAIT/STOP (single button), MUTE/UNMUTE, APPLY, EXIT ---
    LinearLayout btns = new LinearLayout(this);
    btns.setOrientation(LinearLayout.HORIZONTAL);
    btns.setPadding(0, dp(24), 0, dp(12)); // row top/bottom padding
    btns.setGravity(Gravity.CENTER_VERTICAL);

    stateBtn = filledButton("Start", BG, CYAN); // starts black fill
    stateBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if ("CONNECTED".equals(status) || "CONNECTING".equals(status) || "STOPPING".equals(status)) {
          sendStop();
        } else {
          startFlow();
        }
      }
    });
    btns.addView(stateBtn, w());
    btns.addView(space());

    muteBtn = strokeButton("Mute");
    muteBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        boolean newMuted = !muted;
        prefs.edit().putBoolean(KEY_MUTED, newMuted).apply();
        Intent i = new Intent(MainActivity.this, StreamService.class).setAction(ACT_SET_MUTED)
            .putExtra("muted", newMuted);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        muteBtn.setText(newMuted ? "Unmute" : "Mute");
      }
    });
    btns.addView(muteBtn, w());
    btns.addView(space());

    applyBtn = strokeButton("Apply");
    applyBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        savePrefs();
        // push live gain right away
        float val = clamp01(gainSb.getProgress()/100f);
        // Only poke a running stream; DO NOT auto-start from “Apply”
        if ("CONNECTED".equals(status)) {
          Intent i = new Intent(MainActivity.this, StreamService.class)
              .setAction(ACT_SET_GAIN).putExtra("value", val);
          if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
      }
    });
    btns.addView(applyBtn, w());
    btns.addView(space());

    exitBtn = strokeButton("Exit");
    exitBtn.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        sendStop();
        Toast.makeText(MainActivity.this, "Exiting…", Toast.LENGTH_SHORT).show();
        finish();
      }
    });
    btns.addView(exitBtn, w());

    root.addView(btns);

    // --- Status lines ---
    topTv = t("TX 0 B · 0 kb/s · attempts 0");
    root.addView(topTv);

    botTv = t("Disconnected");
    root.addView(botTv);

    // live gain while sliding
    gainSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
        gainTv.setText("gain " + p + "%");
        if (fromUser && "CONNECTED".equals(status) ) {
          float val = clamp01(p/100f);
          prefs.edit().putFloat(KEY_GAIN, val).apply();
          Intent i = new Intent(MainActivity.this, StreamService.class).setAction(ACT_SET_GAIN)
              .putExtra("value", val);
          if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
      }
      @Override public void onStartTrackingTouch(SeekBar sb) {}
      @Override public void onStopTrackingTouch(SeekBar sb) {}
    });

    setContentView(root);
  }

  @Override protected void onResume() {
    super.onResume();
    registerReceiver(br, new IntentFilter(ACT_STATE));
    // update button from last known status
    setStateButtonFor(status);
  }

  @Override protected void onPause() {
    super.onPause();
    try { unregisterReceiver(br); } catch (Throwable ignore) {}
  }

  // ===== helpers =====
  private void sendStop() {
    Intent i = new Intent(this, StreamService.class).setAction(ACT_STOP);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
  }

  private float clamp01(float f){ if (f<0f) return 0f; if (f>1f) return 1f; return f; }
  private int dp(int v){ float d=getResources().getDisplayMetrics().density; return Math.round(v*d); }
  private TextView t(String s){
    TextView x=new TextView(this);
    x.setText(s);
    x.setTextColor(CYAN);
    x.setTextSize(16);
    return x;
  }
  private void styleEdit(EditText e){
    e.setTextColor(CYAN);
    e.setHintTextColor(CYAN_DIM);
    e.setSingleLine(true);
    e.setEllipsize(TextUtils.TruncateAt.END);
    try { e.setBackgroundResource(R.drawable.mn_clickable); } catch (Throwable ignore) {}
  }
  private void styleSeek(SeekBar s){
    if (Build.VERSION.SDK_INT >= 21) {
      s.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
      s.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
    }
  }
  private View space(){
    View v = new View(this);
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
    return v;
  }
  private LinearLayout.LayoutParams w(){
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    return lp;
  }
  private void SpacerParams(TextView t){ t.setText(""); }

  private Button strokeButton(String label){
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setSingleLine(true);
    b.setEllipsize(TextUtils.TruncateAt.END);
    b.setTextColor(CYAN);
    try { b.setBackgroundResource(R.drawable.mn_clickable); } catch (Throwable ignore) {}
    return b;
  }
  private Button filledButton(String label, int fill, int stroke){
    Button b = strokeButton(label);
    b.setBackground(makeBg(fill, stroke));
    return b;
  }
  private GradientDrawable makeBg(int fill, int stroke){
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.RECTANGLE);
    d.setCornerRadius(dp(8));
    d.setColor(fill);
    d.setStroke(dp(2), stroke);
    return d;
  }

  private void setStateButtonFor(String st){
    if ("CONNECTING".equals(st)) {
      stateBtn.setText("Wait");
      stateBtn.setBackground(makeBg(YEL, CYAN));
      stateBtn.setTextColor(BG);
    } else if ("CONNECTED".equals(st)) {
      stateBtn.setText("Stop");
      stateBtn.setBackground(makeBg(BLUE, CYAN));
      stateBtn.setTextColor(WHITE);
    } else if ("STOPPING".equals(st)) {
      stateBtn.setText("Wait");
      stateBtn.setBackground(makeBg(YEL, CYAN));
      stateBtn.setTextColor(BG);
    } else {
      stateBtn.setText("Start");
      stateBtn.setBackground(makeBg(BG, CYAN));
      stateBtn.setTextColor(CYAN);
    }
  }

  private void savePrefs() {
    String host = hostEt.getText().toString().trim();
    if (host.isEmpty()) host = Config.HOST;
    int port;
    try { port = Integer.parseInt(portEt.getText().toString().trim()); }
    catch(Exception e){ port = Config.PORT; }
    if (port < 1 || port > 65535) port = Config.PORT;
    float gain = clamp01(gainSb.getProgress()/100f);
    prefs.edit().putString(KEY_HOST, host)
        .putInt(KEY_PORT, port)
        .putFloat(KEY_GAIN, gain)
        .apply();
  }

  private void startFlow() {
    savePrefs();
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
    // show WAIT immediately
    setStateButtonFor("CONNECTING");
    botTv.setText("CONNECTING");
  }

  @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r){
    super.onRequestPermissionsResult(rc,p,r);
    if (rc==REQ_MIC && r.length>0 && r[0]==PackageManager.PERMISSION_GRANTED) {
      MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
      startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ);
    }
  }

  @Override protected void onActivityResult(int rc, int res, Intent data){
    super.onActivityResult(rc,res,data);
    if (rc!=REQ_PROJ || res!=RESULT_OK || data==null) return;
    Intent svc = new Intent(this, StreamService.class)
        .putExtra("code", res)
        .putExtra("data", data);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
  }
}
