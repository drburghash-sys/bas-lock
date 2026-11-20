package com.bas.lock;

import android.app.Notification;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class BasNotificationListener extends NotificationListenerService {
    private BroadcastReceiver screenReceiver;
    private BroadcastReceiver commandReceiver;
    private WindowManager overlayManager;
    private View wakeAnchor;
    private final android.os.Handler wakeHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                migrateNotificationFilter(
                        FLAG_FILTER_TYPE_CONVERSATIONS |
                        FLAG_FILTER_TYPE_ALERTING |
                        FLAG_FILTER_TYPE_SILENT |
                        FLAG_FILTER_TYPE_ONGOING,
                        null);
            } catch (Exception ignored) {}
        }
        ensureWakeAnchor();
        if (screenReceiver == null) {
            screenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                    boolean enabled = getSharedPreferences("bas_lock_settings", MODE_PRIVATE)
                            .getBoolean("auto_show", true);
                    if (!enabled) return;
                    if (hasActiveCall()) return;
                    if (!Settings.canDrawOverlays(BasNotificationListener.this)) return;
                    KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (km == null || !km.isKeyguardLocked()) return;
                    bringLockScreenForward();
                    wakeHandler.postDelayed(() -> {
                        KeyguardManager km2 = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                        if (km2 != null && km2.isKeyguardLocked()) bringLockScreenForward();
                    }, 220);
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, f);
            }
        }
        if (commandReceiver == null) {
            commandReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    try {
                        if ("com.bas.lock.DISMISS_KEY".equals(action)) {
                            String key = intent.getStringExtra("key");
                            if (key != null && !key.isEmpty()) {
                                cancelNotification(key);
                                NotificationStore.remove(BasNotificationListener.this, key);
                            }
                        } else if ("com.bas.lock.DISMISS_PACKAGE".equals(action)) {
                            String pkg = intent.getStringExtra("pkg");
                            if (pkg != null && !pkg.isEmpty()) {
                                for (StatusBarNotification sbn : getActiveNotifications()) {
                                    if (pkg.equals(sbn.getPackageName())) cancelNotification(sbn.getKey());
                                }
                                NotificationStore.removePackage(BasNotificationListener.this, pkg);
                            }
                        }
                    } catch (Exception ignored) {}
                    sendBroadcast(new Intent("com.bas.lock.REFRESH").setPackage(getPackageName()));
                }
            };
            IntentFilter commands = new IntentFilter();
            commands.addAction("com.bas.lock.DISMISS_KEY");
            commands.addAction("com.bas.lock.DISMISS_PACKAGE");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(commandReceiver, commands, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(commandReceiver, commands);
            }
        }
        syncActive();
    }

    @Override public void onDestroy() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiver = null;
        }
        if (commandReceiver != null) {
            try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
            commandReceiver = null;
        }
        wakeHandler.removeCallbacksAndMessages(null);
        removeWakeAnchor();
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        boolean incomingCall = isCallNotification(n, sbn.getPackageName());
        if (incomingCall) {
            sendBroadcast(new Intent("com.bas.lock.INCOMING_CALL").setPackage(getPackageName()));
        }
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

    private boolean hasActiveCall() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) {
                if (sbn != null && isCallNotification(sbn.getNotification(), sbn.getPackageName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isCallNotification(Notification n, String pkg) {
        if (n == null) return false;
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;
        String p = pkg == null ? "" : pkg.toLowerCase();
        return p.contains("incallui")
                || p.equals("com.samsung.android.dialer")
                || p.equals("com.google.android.dialer")
                || p.equals("com.android.dialer");
    }

    private void ensureWakeAnchor() {
        if (wakeAnchor != null || !Settings.canDrawOverlays(this)) return;
        try {
            overlayManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            FrameLayout anchor = new FrameLayout(this);
            anchor.setBackgroundColor(android.graphics.Color.argb(3, 0, 0, 0));
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    2, 2,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = 0; p.y = 0;
            overlayManager.addView(anchor, p);
            wakeAnchor = anchor;
        } catch (Exception ignored) {
            wakeAnchor = null;
        }
    }

    private void removeWakeAnchor() {
        if (wakeAnchor != null && overlayManager != null) {
            try { overlayManager.removeView(wakeAnchor); } catch (Exception ignored) {}
        }
        wakeAnchor = null;
        overlayManager = null;
    }

    private void bringLockScreenForward() {
        try {
            ensureWakeAnchor();
            Intent i = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("lock_mode", true)
                    .putExtra("wake_from_power", true);
            startActivity(i);
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
