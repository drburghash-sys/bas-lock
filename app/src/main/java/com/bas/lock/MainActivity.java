package com.bas.lock;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.chrono.HijrahDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler timer = new Handler(Looper.getMainLooper());
    private LinearLayout content, prayerRow, notificationsBox;
    private ImageView wallpaper;
    private View backgroundShade;
    private TextView clock, dateLine, hijriLine, nextPrayer, notificationSummary;
    private PrayerTimesCalculator.Times times;
    private double lat = 28.3838, lon = 36.5662; // Tabuk fallback
    private BroadcastReceiver refreshReceiver;

    private static final int NAVY = Color.rgb(6,26,51);
    private static final int NAVY2 = Color.rgb(10,36,68);
    private static final int GOLD = Color.rgb(228,184,95);
    private static final int WHITE = Color.rgb(255,253,248);
    private static final int MUTED = Color.rgb(182,199,217);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(NAVY);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        buildUi();
        safeRefreshAll();

        refreshReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { refreshNotifications(); }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(refreshReceiver, new IntentFilter("com.bas.lock.REFRESH"), RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(refreshReceiver, new IntentFilter("com.bas.lock.REFRESH"));
            }
        } catch (Exception ignored) {}
        timer.post(tick);
    }

    @Override protected void onDestroy() {
        timer.removeCallbacks(tick);
        if (refreshReceiver != null) try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        applyBackground();
        safeRefreshAll();
    }

    @Override public void onBackPressed() {
        requestSecureExit();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { updateClockAndCountdown(); } catch (Exception ignored) {}
            timer.postDelayed(this, 1000);
        }
    };

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3,15,31), NAVY2, Color.rgb(7,76,88)});
        page.setBackground(bg);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setVisibility(View.GONE);
        page.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));

        backgroundShade = new View(this);
        backgroundShade.setBackgroundColor(Color.TRANSPARENT);
        page.addView(backgroundShade, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(18), dp(44), dp(18), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        clock = tv("--:--", 62, WHITE, Typeface.BOLD);
        clock.setLetterSpacing(0.03f);
        content.addView(clock);
        dateLine = tv("", 17, MUTED, Typeface.NORMAL); content.addView(dateLine);
        hijriLine = tv("", 15, GOLD, Typeface.BOLD); content.addView(hijriLine);

        Space s1 = new Space(this); content.addView(s1, new LinearLayout.LayoutParams(1, dp(18)));

        LinearLayout prayerCard = card();
        TextView pTitle = tv("مواقيت الصلاة", 19, WHITE, Typeface.BOLD);
        pTitle.setGravity(Gravity.RIGHT);
        prayerCard.addView(pTitle);
        prayerRow = new LinearLayout(this);
        prayerRow.setOrientation(LinearLayout.HORIZONTAL);
        prayerRow.setGravity(Gravity.CENTER);
        prayerRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        prayerCard.addView(prayerRow, new LinearLayout.LayoutParams(-1, -2));
        nextPrayer = tv("", 18, GOLD, Typeface.BOLD);
        nextPrayer.setGravity(Gravity.CENTER);
        nextPrayer.setPadding(0, dp(14), 0, dp(3));
        prayerCard.addView(nextPrayer);
        content.addView(prayerCard, new LinearLayout.LayoutParams(-1, -2));

        Space s2 = new Space(this); content.addView(s2, new LinearLayout.LayoutParams(1, dp(14)));

        LinearLayout nCard = card();
        LinearLayout nHead = new LinearLayout(this); nHead.setOrientation(LinearLayout.HORIZONTAL);
        nHead.setGravity(Gravity.CENTER_VERTICAL); nHead.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView nTitle = tv("الإشعارات", 19, WHITE, Typeface.BOLD);
        nHead.addView(nTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = pill("مسح العرض", false);
        clear.setOnClickListener(v -> { NotificationStore.clear(this); refreshNotifications(); });
        nHead.addView(clear);
        nCard.addView(nHead);
        notificationSummary = tv("", 12, GOLD, Typeface.BOLD);
        notificationSummary.setGravity(Gravity.RIGHT);
        notificationSummary.setPadding(0, dp(6), 0, dp(2));
        nCard.addView(notificationSummary);
        notificationsBox = new LinearLayout(this); notificationsBox.setOrientation(LinearLayout.VERTICAL);
        nCard.addView(notificationsBox, new LinearLayout.LayoutParams(-1, -2));
        content.addView(nCard, new LinearLayout.LayoutParams(-1, -2));

        Space s3 = new Space(this); content.addView(s3, new LinearLayout.LayoutParams(1, dp(16)));

        LinearLayout actions1 = new LinearLayout(this);
        actions1.setOrientation(LinearLayout.HORIZONTAL); actions1.setGravity(Gravity.CENTER); actions1.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView access = pill("تفعيل الإشعارات", true);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        actions1.addView(access);
        TextView auto = pill("الظهور عند القفل", false);
        boolean autoOn = getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getBoolean("auto_show", true);
        boolean lockReady = Settings.canDrawOverlays(this) && hasNotificationAccess();
        if (autoOn && lockReady) auto.setText("الظهور عند القفل ✓");
        else if (autoOn) auto.setText("الظهور عند القفل ⚠");
        auto.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this) || !hasNotificationAccess()) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                toggleAutoShow(auto);
            }
        });
        actions1.addView(auto);
        content.addView(actions1);

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL); actions2.setGravity(Gravity.CENTER); actions2.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        actions2.setPadding(0, dp(8), 0, 0);

        TextView close = pill("إغلاق BAS Lock", false);
        close.setOnClickListener(v -> requestSecureExit());
        actions2.addView(close);

        TextView location = pill("تحديث الموقع", false);
        location.setOnClickListener(v -> {
            refreshLocation();
            Toast.makeText(this, "سيتم استخدام موقع الجهاز بعد منح الإذن، وإلا فسيبقى تبوك افتراضيًا.", Toast.LENGTH_SHORT).show();
        });
        actions2.addView(location);

        TextView settings = pill("الإعدادات", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        actions2.addView(settings);
        content.addView(actions2);

        TextView note = tv("عند إغلاق BAS Lock من شاشة القفل سيطلب النظام البصمة أو وسيلة قفل الجهاز، ثم تظهر الشاشة الرئيسية مباشرة.", 12, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER); note.setPadding(dp(12), dp(12), dp(12), 0);
        content.addView(note);

        setContentView(page);
        applyBackground();
    }

    private void refreshAll() {
        computePrayerTimes();
        renderPrayerTimes();
        refreshNotifications();
        updateClockAndCountdown();
    }

    private void safeRefreshAll() {
        try {
            refreshAll();
        } catch (Exception e) {
            try {
                if (clock != null) clock.setText("--:--");
                if (dateLine != null) dateLine.setText("BAS Lock يعمل بوضع الأمان");
                if (hijriLine != null) hijriLine.setText("");
                if (nextPrayer != null) nextPrayer.setText("تعذر تحديث بعض البيانات مؤقتًا");
                if (notificationSummary != null) notificationSummary.setText("يمكن متابعة الإعدادات ثم إعادة فتح التطبيق");
            } catch (Exception ignored) {}
        }
    }

    private void refreshLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
            return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location x = lm.getLastKnownLocation(p);
                if (x != null && (best == null || x.getAccuracy() < best.getAccuracy())) best = x;
            }
            if (best != null) {
                lat = best.getLatitude(); lon = best.getLongitude();
                safeRefreshAll();
            }
        } catch (Exception ignored) {}
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshLocation();
            }
            safeRefreshAll();
        }
    }

    private void computePrayerTimes() {
        LocalDate today = LocalDate.now();
        boolean ramadan = false;
        try { ramadan = HijrahDate.from(today).get(ChronoField.MONTH_OF_YEAR) == 9; } catch (Exception ignored) {}
        times = PrayerTimesCalculator.calculate(today, lat, lon, ZoneId.systemDefault(), ramadan);
        SharedPreferences p = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        times = applyOffsets(times, p);
    }

    private PrayerTimesCalculator.Times applyOffsets(PrayerTimesCalculator.Times base, SharedPreferences p) {
        return new PrayerTimesCalculator.Times(
                base.fajr.plusMinutes(p.getInt("offset_fajr", 0)),
                base.sunrise.plusMinutes(p.getInt("offset_sunrise", 0)),
                base.dhuhr.plusMinutes(p.getInt("offset_dhuhr", 0)),
                base.asr.plusMinutes(p.getInt("offset_asr", 0)),
                base.maghrib.plusMinutes(p.getInt("offset_maghrib", 0)),
                base.isha.plusMinutes(p.getInt("offset_isha", 0)));
    }

    private void renderPrayerTimes() {
        prayerRow.removeAllViews();
        Map<String, ZonedDateTime> map = times.asMap();
        ZonedDateTime now = ZonedDateTime.now();
        String next = getNextPrayerName(now);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
        for (Map.Entry<String, ZonedDateTime> e : map.entrySet()) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            int color = e.getKey().equals(next) ? GOLD : WHITE;
            TextView name = tv(e.getKey(), 12, color, Typeface.BOLD); name.setGravity(Gravity.CENTER);
            TextView t = tv(arabicDigits(f.format(e.getValue())), 13, color, Typeface.NORMAL); t.setGravity(Gravity.CENTER);
            cell.addView(name); cell.addView(t);
            prayerRow.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
    }

    private void updateClockAndCountdown() {
        ZonedDateTime now = ZonedDateTime.now();
        clock.setText(arabicDigits(DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(now)));
        Locale ar = new Locale("ar", "SA");
        dateLine.setText(DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", ar).format(now));
        try {
            HijrahDate h = HijrahDate.now();
            int d = h.get(ChronoField.DAY_OF_MONTH), m = h.get(ChronoField.MONTH_OF_YEAR), y = h.get(ChronoField.YEAR);
            String[] hm = {"محرم","صفر","ربيع الأول","ربيع الآخر","جمادى الأولى","جمادى الآخرة","رجب","شعبان","رمضان","شوال","ذو القعدة","ذو الحجة"};
            hijriLine.setText(arabicDigits(String.valueOf(d)) + " " + hm[m-1] + " " + arabicDigits(String.valueOf(y)) + " هـ");
        } catch (Exception ignored) { hijriLine.setText(""); }

        String name = getNextPrayerName(now);
        ZonedDateTime target = getPrayer(name);
        if (target == null || target.isBefore(now)) {
            LocalDate tomorrowDate = LocalDate.now().plusDays(1);
            boolean ramadan = false;
            try { ramadan = HijrahDate.from(tomorrowDate).get(ChronoField.MONTH_OF_YEAR) == 9; } catch (Exception ignored) {}
            PrayerTimesCalculator.Times tomorrow = PrayerTimesCalculator.calculate(tomorrowDate, lat, lon, ZoneId.systemDefault(), ramadan);
            tomorrow = applyOffsets(tomorrow, getSharedPreferences("bas_lock_settings", MODE_PRIVATE));
            name = "الفجر"; target = tomorrow.fajr;
        }
        Duration d = Duration.between(now, target);
        long totalMin = Math.max(0, d.toMinutes());
        long h = totalMin / 60, m = totalMin % 60;
        nextPrayer.setText("الصلاة القادمة: " + name + " — بعد " + arabicDigits(h + ":" + String.format(Locale.US, "%02d", m)));
    }

    private String getNextPrayerName(ZonedDateTime now) {
        if (now.isBefore(times.fajr)) return "الفجر";
        if (now.isBefore(times.dhuhr)) return "الظهر"; // sunrise is displayed, not treated as a prayer
        if (now.isBefore(times.asr)) return "العصر";
        if (now.isBefore(times.maghrib)) return "المغرب";
        if (now.isBefore(times.isha)) return "العشاء";
        return "الفجر";
    }

    private ZonedDateTime getPrayer(String name) {
        switch (name) {
            case "الفجر": return times.fajr;
            case "الظهر": return times.dhuhr;
            case "العصر": return times.asr;
            case "المغرب": return times.maghrib;
            case "العشاء": return times.isha;
            default: return null;
        }
    }

    private void refreshNotifications() {
        notificationsBox.removeAllViews();
        List<NotificationStore.Item> items = NotificationStore.load(this);
        int important = 0, review = 0, other = 0;
        for (NotificationStore.Item x : items) {
            if (x.level >= 3) important++; else if (x.level == 2) review++; else other++;
        }
        notificationSummary.setText("مهم " + arabicDigits(String.valueOf(important)) + "  •  مراجعة " + arabicDigits(String.valueOf(review)) + "  •  أخرى " + arabicDigits(String.valueOf(other)));
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isDeviceLocked();
        boolean showUnlockedText = getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getBoolean("show_text_unlocked", true);
        int shown = 0;
        for (NotificationStore.Item x : items) {
            if (shown >= 7) break;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(14));
            gd.setColor(Color.argb(75, 255, 255, 255));
            gd.setStroke(dp(1), x.level >= 3 ? GOLD : Color.argb(60,255,255,255));
            row.setBackground(gd);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.setMargins(0, dp(7), 0, 0);
            row.setLayoutParams(rp);

            String dot = x.level >= 3 ? "● " : x.level == 2 ? "• " : "";
            TextView app = tv(dot + (TextUtils.isEmpty(x.app) ? x.pkg : x.app), 14, x.level >= 3 ? GOLD : WHITE, Typeface.BOLD);
            app.setGravity(Gravity.RIGHT); row.addView(app);
            String summary;
            if (locked || !showUnlockedText) summary = TextUtils.isEmpty(x.title) ? "إشعار جديد" : x.title;
            else summary = TextUtils.isEmpty(x.text) ? x.title : (TextUtils.isEmpty(x.title) ? x.text : x.title + " — " + x.text);
            TextView body = tv(summary, 13, MUTED, Typeface.NORMAL); body.setGravity(Gravity.RIGHT); body.setMaxLines(2); row.addView(body);

            TextView manage = pill("إعدادات إشعارات التطبيق", false);
            manage.setTextSize(11);
            manage.setOnClickListener(v -> openAppNotificationSettings(x.pkg));
            row.addView(manage);

            row.setOnClickListener(v -> {
                try {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(x.pkg);
                    if (launch != null) startActivity(launch);
                } catch (Exception ignored) {}
            });
            notificationsBox.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView none = tv("لا توجد إشعارات محفوظة. فعّل صلاحية الوصول إلى الإشعارات من الزر أدناه.", 13, MUTED, Typeface.NORMAL);
            none.setGravity(Gravity.CENTER); none.setPadding(dp(8), dp(16), dp(8), dp(8)); notificationsBox.addView(none);
        }
    }



    private void applyBackground() {
        if (wallpaper == null || backgroundShade == null) return;
        String saved = getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getString("background_uri", "");
        if (TextUtils.isEmpty(saved)) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            backgroundShade.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        try {
            wallpaper.setImageURI(Uri.parse(saved));
            wallpaper.setVisibility(View.VISIBLE);
            backgroundShade.setBackgroundColor(Color.argb(145, 2, 14, 30));
        } catch (Exception e) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            backgroundShade.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void openAppNotificationSettings(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private void requestSecureExit() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            try {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() {
                        finishAndRemoveTask();
                    }
                    @Override public void onDismissError() {
                        Toast.makeText(MainActivity.this, "تعذر فتح قفل الجهاز", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        requestBiometricExit();
    }

    private void requestBiometricExit() {
        if (Build.VERSION.SDK_INT < 28) {
            finishAndRemoveTask();
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("إغلاق BAS Lock")
                    .setSubtitle("أكد بالبصمة للمتابعة")
                    .setNegativeButton("إلغاء", getMainExecutor(), (dialog, which) -> {})
                    .build();
            CancellationSignal signal = new CancellationSignal();
            prompt.authenticate(signal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    finishAndRemoveTask();
                }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(MainActivity.this, "تعذر استخدام البصمة: " + errString, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "البصمة غير متاحة. تأكد من تسجيل بصمة وقفل شاشة آمن.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void toggleAutoShow(TextView v) {
        SharedPreferences p = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        boolean next = !p.getBoolean("auto_show", true);
        p.edit().putBoolean("auto_show", next).apply();
        Toast.makeText(this, next ? "تم تفعيل محاولة الظهور التلقائي عند إضاءة شاشة القفل" : "تم إيقاف الظهور التلقائي", Toast.LENGTH_SHORT).show();
        v.setText(next ? "الظهور عند القفل ✓" : "الظهور عند القفل");
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(24)); g.setColor(Color.argb(150, 5, 27, 50));
        g.setStroke(dp(1), Color.argb(90, 228, 184, 95)); l.setBackground(g); return l;
    }

    private TextView pill(String text, boolean gold) {
        TextView v = tv(text, 13, gold ? NAVY : WHITE, Typeface.BOLD); v.setGravity(Gravity.CENTER); v.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(18)); g.setColor(gold ? GOLD : Color.argb(55,255,255,255));
        g.setStroke(dp(1), gold ? GOLD : Color.argb(70,255,255,255)); v.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(dp(5),0,dp(5),0); v.setLayoutParams(p); return v;
    }

    private TextView tv(String s, int sp, int color, int style) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setTypeface(Typeface.create("sans", style));
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL); return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String arabicDigits(String s) {
        char[] a = {'٠','١','٢','٣','٤','٥','٦','٧','٨','٩'}; StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? a[c-'0'] : c); return b.toString();
    }
}
