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
        refreshLocation();
        refreshAll();

        refreshReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { refreshNotifications(); }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, new IntentFilter("com.bas.lock.REFRESH"), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, new IntentFilter("com.bas.lock.REFRESH"));
        }
        timer.post(tick);
    }

    @Override protected void onDestroy() {
        timer.removeCallbacks(tick);
        if (refreshReceiver != null) try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshLocation();
        refreshAll();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClockAndCountdown();
            timer.postDelayed(this, 1000);
        }
    };

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3,15,31), NAVY2, Color.rgb(7,76,88)});
        scroll.setBackground(bg);

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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL); actions.setGravity(Gravity.CENTER); actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView access = pill("تفعيل الإشعارات", true);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        actions.addView(access);
        TextView auto = pill("الظهور عند القفل", false);
        boolean autoOn = getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getBoolean("auto_show", true);
        if (autoOn) auto.setText("الظهور عند القفل ✓");
        auto.setOnClickListener(v -> toggleAutoShow(auto));
        actions.addView(auto);
        TextView settings = pill("الإعدادات", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        actions.addView(settings);
        content.addView(actions);

        TextView note = tv("يظل قفل سامسونج والبصمة والـ PIN كما هي. BAS Lock يعرض لوحة معلومات فوق شاشة القفل عند سماح النظام بذلك.", 12, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER); note.setPadding(dp(12), dp(12), dp(12), 0);
        content.addView(note);

        setContentView(scroll);
    }

    private void refreshAll() {
        computePrayerTimes();
        renderPrayerTimes();
        refreshNotifications();
        updateClockAndCountdown();
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
            if (best != null) { lat = best.getLatitude(); lon = best.getLongitude(); }
        } catch (Exception ignored) {}
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7) { refreshLocation(); refreshAll(); }
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
