package com.bas.lock;

import android.app.Notification;
import android.app.KeyguardManager;
import android.content.*;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class BasNotificationListener extends NotificationListenerService {
    private BroadcastReceiver screenReceiver;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                    boolean enabled = getSharedPreferences("bas_lock_settings", MODE_PRIVATE)
                            .getBoolean("auto_show", true);
                    if (!enabled) return;
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (km == null || !km.isKeyguardLocked()) return;
                    // Best-effort only. Modern Android may block background activity launches.
                    try {
                        Intent i = new Intent(BasNotificationListener.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                .putExtra("lock_mode", true);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
        }
        syncActive();
    }

    @Override public void onDestroy() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiver = null;
        }
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        NotificationStore.Item item = new NotificationStore.Item();
        item.key = sbn.getKey();
        item.pkg = sbn.getPackageName();
        item.app = appName(item.pkg);
        item.title = safe(e.getCharSequence(Notification.EXTRA_TITLE));
        item.text = safe(e.getCharSequence(Notification.EXTRA_TEXT));
        item.when = sbn.getPostTime();
        item.level = classify(n, item.pkg, item.title, item.text);
        NotificationStore.upsert(this, item);
        sendBroadcast(new Intent("com.bas.lock.REFRESH").setPackage(getPackageName()));
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) NotificationStore.remove(this, sbn.getKey());
        sendBroadcast(new Intent("com.bas.lock.REFRESH").setPackage(getPackageName()));
    }

    private void syncActive() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
        } catch (Exception ignored) {}
    }

    private int classify(Notification n, String pkg, String title, String text) {
        String cat = n.category == null ? "" : n.category;
        String all = (pkg + " " + title + " " + text).toLowerCase();
        if (Notification.CATEGORY_CALL.equals(cat) || Notification.CATEGORY_ALARM.equals(cat)) return 3;
        if (Notification.CATEGORY_MESSAGE.equals(cat) || Notification.CATEGORY_EMAIL.equals(cat)) return 2;
        if (all.contains("otp") || all.contains("verification") || all.contains("رمز") ||
                all.contains("مستشفى") || all.contains("hospital") || all.contains("bank") || all.contains("بنك")) return 3;
        return 1;
    }

    private String appName(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    private static String safe(CharSequence x) { return x == null ? "" : x.toString(); }
}
