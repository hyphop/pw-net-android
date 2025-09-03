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
import android.util.Log;

import java.net.InetAddress;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet6Address;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {
  private MdnsDiscoverer mdns;
  private LinearLayout candidatesLayout;
  private TextView mdnsLabel;
  private int mdnsEvents = 0;


  private static final String TAG = "MainActivity";
  private static final String PREFS="mn_prefs";
  private static final String KEY_HOST="host", KEY_PORT="port", KEY_GAIN="gain", KEY_MUTED="muted";
  private static final String ACT_STATE="org.example.mininative.STATE";
  private static final String ACT_STOP="org.example.mininative.STOP";
  private static final String ACT_SET_GAIN="org.example.mininative.SET_GAIN";
  private static final String ACT_SET_MUTED="org.example.mininative.SET_MUTED";

  private static final int REQ_MIC=1001, REQ_PROJ=1002, REQ_POST=1003;

  // colors
  private static final int CYAN  = 0xFF00FFFF;
  private static final int GRAY  = 0xFF666666;
  private static final int CYAN_DIM = 0xFF009999;
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


  private static java.util.List<String> getLocalWifiIPs(Context ctx) {
    String ipv4 = null, ipv6 = null;

    // Prefer active Wi-Fi network (API 21+)
    try {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = (cm != null) ? cm.getActiveNetwork() : null;
        if (n != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        java.net.InetAddress a = la.getAddress();
                        if (a.isLoopbackAddress()) continue;
                        if (a instanceof java.net.Inet4Address) {
                            ipv4 = a.getHostAddress();
                        } else if (a instanceof java.net.Inet6Address) {
                            Inet6Address a6 = (Inet6Address) a;
                            if (!a6.isLinkLocalAddress() && !a6.isMulticastAddress()) {
                                String s = a6.getHostAddress();
                                int pct = s.indexOf('%'); // drop scope id if present
                                ipv6 = (pct >= 0) ? s.substring(0, pct) : s;
                            }
                        }
                    }
                }
            }
        }
    } catch (Throwable ignore) {}

    // Fallback: scan wlan*/wifi* interfaces
    try {
        java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements() && (ipv4 == null || ipv6 == null)) {
            java.net.NetworkInterface nif = en.nextElement();
            if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
            String ln = (nif.getName() != null) ? nif.getName().toLowerCase(Locale.ROOT) : "";
            if (!(ln.startsWith("wlan") || ln.contains("wifi"))) continue;

            java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
            while (addrs.hasMoreElements() && (ipv4 == null || ipv6 == null)) {
                java.net.InetAddress a = addrs.nextElement();
                if (a.isLoopbackAddress()) continue;

                if (a instanceof java.net.Inet4Address && ipv4 == null) {
                    ipv4 = a.getHostAddress();
                } else if (a instanceof java.net.Inet6Address && ipv6 == null) {
                    Inet6Address a6 = (Inet6Address) a;
                    if (!a6.isLinkLocalAddress() && !a6.isMulticastAddress()) {
                        String s = a6.getHostAddress();
                        int pct = s.indexOf('%');
                        ipv6 = (pct >= 0) ? s.substring(0, pct) : s;
                    }
                }
            }
        }
    } catch (Throwable ignore) {}

    java.util.ArrayList<String> out = new java.util.ArrayList<>(2);
    if (ipv4 != null) out.add(ipv4);
    if (ipv6 != null) out.add(ipv6);
    return out;
  }

  private final BroadcastReceiver br = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      if (!ACT_STATE.equals(i.getAction())) return;
      status   = i.getStringExtra("status");
      long tx  = i.getLongExtra("tx", 0L);
      int kbps = i.getIntExtra("kbps", 0);
      int attempts = i.getIntExtra("attempts", 0);
      muted = i.getBooleanExtra("muted", false);
      float g = i.getFloatExtra("gain", prefs.getFloat(KEY_GAIN, 1f));

      setStateButtonFor(status);
      topTv.setText("TX " + tx + " B  " + kbps + " kb/s  attempts " + attempts);
      botTv.setText(status);
      gainTv.setText("gain " + Math.round(g * 100f) + "%");
      muteBtn.setText(muted ? "Unmute" : "Mute");
    }
  };

  private void updateMdnsLabel() {
    int c = (candidatesLayout != null) ? candidatesLayout.getChildCount() : 0;
    if ( mdnsLabel != null ) mdnsLabel.setText("mDNS List [" + c + "/" + mdnsEvents + "]");
    Log.i(TAG, "update mdns " + c + "/" + mdnsEvents );
  }

  private void updateMdnsLabel2Zero() {
    if ( candidatesLayout != null ) {
        candidatesLayout.removeAllViews();
        mdnsEvents=0;
        updateMdnsLabel();
    }
  }

    private boolean hasItemWithText(LinearLayout layout, String text) {
    for (int i = 0; i < layout.getChildCount(); i++) {
        View v = layout.getChildAt(i);
        if (v instanceof TextView) {
            CharSequence existing = ((TextView) v).getText();
            if (text.equals(existing)) return true;
        }
    }
    return false;
    }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    Log.i(TAG, "created");

    Intent mw = new Intent(this, MediaWatchService.class);
    if (Build.VERSION.SDK_INT >= 26) {
    startForegroundService(mw);
    } else {
    startService(mw);
    }

    // just log the IPs on start
    for (String ip : getLocalWifiIPs(this)) {
        Log.i(TAG, "Local IP: " + ip);
    }

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(14);
    root.setPadding(pad, pad, pad, pad);
    root.setGravity(Gravity.TOP|Gravity.START);
    root.setBackgroundColor(BG);

    // Host field
    hostEt = new EditText(this);
    hostEt.setHint("host");
    hostEt.setSingleLine(true);
    hostEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    hostEt.setText(prefs.getString(KEY_HOST, Config.HOST));
    styleEdit(hostEt);

    LinearLayout.LayoutParams lpHost = new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
    );
    lpHost.setMargins(0, 0, 0, dp(8));  // top=8dp, bottom=4dp spacing
                                        //
    root.addView(hostEt,lpHost);

    // Port field
    portEt = new EditText(this);
    portEt.setHint("port");
    portEt.setSingleLine(true);
    portEt.setInputType(InputType.TYPE_CLASS_NUMBER);
    portEt.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(5) });
    portEt.setText(String.valueOf(prefs.getInt(KEY_PORT, Config.PORT)));
    styleEdit(portEt);
    root.addView(portEt);

    // Gain header
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

    // Gain slider
    gainSb = new SeekBar(this);
    gainSb.setMax(100);
    float g0 = clamp01(prefs.getFloat(KEY_GAIN, 1.0f));
    gainSb.setProgress(Math.round(g0*100));
    gainTv.setText("gain " + Math.round(g0*100) + "%");
    styleSeek(gainSb);
    root.addView(gainSb);

    // Buttons row
    LinearLayout btns = new LinearLayout(this);
    btns.setOrientation(LinearLayout.HORIZONTAL);
    btns.setPadding(0, dp(24), 0, dp(12));
    btns.setGravity(Gravity.CENTER_VERTICAL);

    stateBtn = filledButton("Start", BG, CYAN);
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
        float val = clamp01(gainSb.getProgress()/100f);
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
        Log.i(TAG,"Exit pressed");
        sendStop();
        Toast.makeText(MainActivity.this, "Exiting", Toast.LENGTH_SHORT).show();
        stopService(new Intent(MainActivity.this, StreamService.class));
        stopService(new Intent(MainActivity.this, MediaWatchService.class));
        if (android.os.Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
        else finish();
      }
    });
    btns.addView(exitBtn, w());
    root.addView(btns);

    // Status lines
    topTv = t("TX 0 B  0 kb/s  attempts 0");
    topTv.setClickable(true);

topTv.setOnClickListener(new View.OnClickListener() {
    @Override public void onClick(View v) {
        java.util.List<String> ips = getLocalWifiIPs(MainActivity.this);
        if (ips.isEmpty()) {
            android.widget.Toast.makeText(MainActivity.this, "No Wi-Fi IPs found", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = android.text.TextUtils.join(" ", ips);
        android.widget.Toast.makeText(MainActivity.this, "Local/WiFi IPs: " + msg, android.widget.Toast.LENGTH_LONG).show();

        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("local-ips", msg));
    }
});

    root.addView(topTv);
    botTv = t("Disconnected");
    root.addView(botTv);

    // mDNS label + list (UNDER status)
    mdnsLabel = t("mDNS List [0/0]");
    mdnsLabel.setTextColor(YEL);
    mdnsLabel.setClickable(true);
    mdnsLabel.setPadding(0, dp(12), 0, dp(4));

    mdnsLabel.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        // Do something on click, e.g. refresh list
        Toast.makeText(MainActivity.this, "mDNS list cleared", Toast.LENGTH_SHORT).show();
        updateMdnsLabel2Zero();
    }
    });

    root.addView(mdnsLabel);

    candidatesLayout = new LinearLayout(this);
    candidatesLayout.setOrientation(LinearLayout.VERTICAL);
    candidatesLayout.setPadding(0, dp(4), 0, 0);
    root.addView(candidatesLayout);

    // Gain live updates
    gainSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
        gainTv.setText("gain " + p + "%");
        if (fromUser && "CONNECTED".equals(status)) {
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

  private void mdns_setup() {
    if (mdns != null) return;

    mdns = new MdnsDiscoverer(getApplicationContext(), Config.MDNS_SRV_NAME,
      new MdnsDiscoverer.Callback() {
        @Override
        public void onService(final InetAddress host, final int port, final String[] txt) {
          runOnUiThread(new Runnable() {
            @Override public void run() {
              if ( txt != null ) {
                addCandidate(host, port, txt);
              } else {
                mdnsEvents++;
                updateMdnsLabel();
              }
            }
          });
        }
      });

    Log.i(TAG, "mdns init...");
  }

private void addCandidate(final InetAddress host, final int port, final String[] txt) {
    if (candidatesLayout == null) return;

    // Default to host if no TXT or first TXT empty
    String displayTxt;
    if (txt != null && txt.length > 0) {
    // join all TXT strings with spaces
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < txt.length; i++) {
        if (txt[i] != null && !txt[i].trim().isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(txt[i].trim());
        }
    }
    displayTxt = sb.toString();
    } else {
        displayTxt = ""; // no TXT records
    }

    // Always show IP:PORT, then optional TXT
    String line = host.getHostAddress() + ":" + port;
    if (!displayTxt.isEmpty()) {
        line += " " + displayTxt;
    }

    if (hasItemWithText(candidatesLayout, line)) {
        return; // no need dub list
    }

    TextView item = new TextView(this);
    item.setText(line);
    item.setTextColor(CYAN_DIM);
    item.setPadding(dp(8), dp(4), dp(8), dp(4));
    item.setTextSize(16);
    item.setBackground(makeBg(BG, CYAN_DIM));

    // Click → copy to inputs
    item.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            hostEt.setText(host.getHostAddress());
            portEt.setText(String.valueOf(port));
            Toast.makeText(MainActivity.this,
                "Selected " + host.getHostAddress() + ":" + port,
                Toast.LENGTH_SHORT).show();
        }
    });

    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
    );

    lp.setMargins(dp(0), dp(4), dp(0), dp(0));
    candidatesLayout.addView(item, lp);

    mdnsEvents++;
    updateMdnsLabel();
  }

  @Override protected void onStart() {
    super.onStart();
    Log.i(TAG, "onStart");
    mdns_setup();
    if (candidatesLayout != null) candidatesLayout.removeAllViews(); // fresh list
    mdnsEvents = 0;                      // reset total events
    updateMdnsLabel();                   // shows [0/0]
    mdns.start();
    Log.i(TAG, "mdns start()");
  }


  @Override protected void onResume() {
    super.onResume();
    Log.i(TAG, "resume");
    registerReceiver(br, new IntentFilter(ACT_STATE));
    setStateButtonFor(status);
  }

  @Override protected void onPause() {
    super.onPause();
    Log.i(TAG, "pause");
    try { unregisterReceiver(br); } catch (Throwable ignore) {}
  }

  @Override protected void onStop() {
    Log.i(TAG, "onStop isFinishing=" + isFinishing());
    if (mdns != null) {
      Log.i(TAG, "mDNS discovery stopping...");
      mdns.stop();
    }
    super.onStop();
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    try { if (mdns != null) mdns.stop(); } catch (Throwable ignore) {}
    Log.i(TAG, "onDestroy");
  }

  // ===== helpers =====
  private void sendStop() {
    Intent i = new Intent(this, StreamService.class).setAction(ACT_STOP);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
  }

  private float clamp01(float f){ if (f<0f) return 0f; if (f>1f) return 1f; return f; }
  private int dp(int v){ float d=getResources().getDisplayMetrics().density; return Math.round(v*d); }
  private TextView t(String s){ TextView x=new TextView(this); x.setText(s); x.setTextColor(CYAN); x.setTextSize(16); return x; }
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
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
    Log.i(TAG, "save");
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
    Log.i(TAG, "start");
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
