package org.example.mininative;

public class NLService extends android.service.notification.NotificationListenerService {
  @Override public void onListenerConnected() {
    android.util.Log.i("MiniNativeStream", "NotificationListener connected");
  }
}
