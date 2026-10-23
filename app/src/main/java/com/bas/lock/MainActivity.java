package com.bas.lock;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.GeomagneticField;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private final Handler timer = new Handler(Looper.getMainLooper());
    private LinearLayout content, prayerRow, notificationsBox, prayerDetailsContainer;
    private TextView prayerSectionTitle;
    private boolean prayerDetailsExpanded = false;
    private ScrollView mainScroll;
    private final Set<String> expandedApps = new HashSet<>();
    private ImageView wallpaper;
    private View backgroundShade;
    private TextView clock, dateLine, hijriLine, locationName, nextPrayer, notificationSummary;
    private QiblaCompassView qiblaCompass;
    private TextView qiblaDegrees, qiblaStatus, qiblaHeadingLabel;
    private boolean hasConfirmedQiblaLocation = false;
    private boolean compassTilted = false;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private double qiblaBearing, currentTrueHeading = Double.NaN;
    private final SensorEventListener compassListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
            float[] matrix = new float[9];
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            float[] angles = new float[3];
            SensorManager.getOrientation(matrix, angles);
            // Orientation is designed for the phone lying mostly flat, screen up.
            compassTilted = Math.abs(Math.toDegrees(angles[1])) > 65
                    || Math.abs(Math.toDegrees(angles[2])) > 65;
            if (compassTilted) {
                currentTrueHeading = Double.NaN;
            } else {
                double magneticHeading = Math.toDegrees(angles[0]);
                GeomagneticField field = new GeomagneticField(
                        (float) lat, (float) lon, 0f, System.currentTimeMillis());
                currentTrueHeading = (magneticHeading + field.getDeclination() + 360) % 360;
            }
            updateQiblaCompass();
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            if (sensor != null && sensor.getType() == Sensor.TYPE_ROTATION_VECTOR
                    && accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                    && qiblaStatus != null) {
                qiblaStatus.setText("البوصلة غير دقيقة؛ أبعد الهاتف عن المعادن وحرّكه على شكل ٨");
            }
        }
    };
    private TextView topWeatherIcon, topWeatherTemp, topWeatherCondition, topBatteryIcon, topBatteryPercent, topBatteryLabel;
    private long lastWeatherFetch = 0L, lastBatteryUpdate = 0L;
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
        applyImmersiveNavigationLock();
        loadSavedLocation();
        buildUi();
        loadCachedTopWeather();
        updateBatteryStatus();
        refreshTopWeather(true);
        safeRefreshAll();

        refreshReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if ("com.bas.lock.INCOMING_CALL".equals(i.getAction())) {
                    try { finishAndRemoveTask(); } catch (Exception e) { finish(); }
                    return;
                }
                refreshNotifications();
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.bas.lock.REFRESH");
            filter.addAction("com.bas.lock.INCOMING_CALL");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(refreshReceiver, filter);
            }
        } catch (Exception ignored) {}
        timer.post(tick);
    }

    @Override protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(compassListener);
        super.onPause();
    }

    @Override protected void onDestroy() {
        timer.removeCallbacks(tick);
        if (refreshReceiver != null) try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        applyImmersiveNavigationLock();
        loadSavedLocation();
        applyBackground();
        updateBatteryStatus();
        refreshTopWeather(false);
        safeRefreshAll();
        updateLocationName();
        // Keep the motion sensor off while the prayer details are collapsed.
        if (prayerDetailsExpanded) startCompass();
        if (LocationSupport.permitted(this)) refreshLocation();
        scrollToTop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveNavigationLock();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        scrollToTop();
    }

    private void scrollToTop() {
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, 0));
        }
    }

    @Override public void onBackPressed() {
        requestSecureExit();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                updateClockAndCountdown();
                long now = System.currentTimeMillis();
                if (now - lastBatteryUpdate > 30000L) updateBatteryStatus();
                if (now - lastWeatherFetch > 15L * 60L * 1000L) refreshTopWeather(false);
            } catch (Exception ignored) {}
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

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setBackgroundColor(Color.TRANSPARENT);
        page.addView(mainScroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(dp(18), dp(44), dp(18), dp(28));
        mainScroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout topNav = new LinearLayout(this);
        topNav.setOrientation(LinearLayout.HORIZONTAL);
        topNav.setGravity(Gravity.RIGHT);
        topNav.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        topNav.setPadding(0, 0, 0, dp(8));

        TextView topPlatform = pill("BAS Platform", true);
        topPlatform.setOnClickListener(v -> openBasPlatformSecurely());
        topNav.addView(topPlatform);
        content.addView(topNav, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout topStatus = new LinearLayout(this);
        topStatus.setOrientation(LinearLayout.HORIZONTAL);
        topStatus.setGravity(Gravity.CENTER_VERTICAL);
        topStatus.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        topStatus.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable topStatusBg = new GradientDrawable();
        topStatusBg.setCornerRadius(dp(24));
        topStatusBg.setColor(Color.argb(58, 4, 22, 42));
        topStatusBg.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        topStatus.setBackground(topStatusBg);

        LinearLayout weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.CENTER);
        weatherBox.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        topWeatherIcon = tv("☀️", 27, WHITE, Typeface.NORMAL);
        topWeatherIcon.setGravity(Gravity.CENTER);
        topWeatherTemp = tv("--°", 21, WHITE, Typeface.BOLD);
        topWeatherTemp.setGravity(Gravity.CENTER);
        topWeatherCondition = tv("الطقس", 10, MUTED, Typeface.NORMAL);
        topWeatherCondition.setGravity(Gravity.CENTER);
        weatherBox.addView(topWeatherIcon);
        weatherBox.addView(topWeatherTemp);
        weatherBox.addView(topWeatherCondition);
        topStatus.addView(weatherBox, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        clock = tv("--:--", 54, WHITE, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setLetterSpacing(0.03f);
        clock.setOnClickListener(v -> openClockApp());
        clockBox.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        TextView clockHint = tv("الساعة", 10, MUTED, Typeface.NORMAL);
        clockHint.setGravity(Gravity.CENTER);
        clockBox.addView(clockHint);
        topStatus.addView(clockBox, new LinearLayout.LayoutParams(0, -2, 1.55f));

        LinearLayout batteryBox = new LinearLayout(this);
        batteryBox.setOrientation(LinearLayout.VERTICAL);
        batteryBox.setGravity(Gravity.CENTER);
        topBatteryIcon = tv("🔋", 27, WHITE, Typeface.NORMAL);
        topBatteryIcon.setGravity(Gravity.CENTER);
        topBatteryPercent = tv("--٪", 21, WHITE, Typeface.BOLD);
        topBatteryPercent.setGravity(Gravity.CENTER);
        topBatteryLabel = tv("البطارية", 10, MUTED, Typeface.NORMAL);
        topBatteryLabel.setGravity(Gravity.CENTER);
        batteryBox.addView(topBatteryIcon);
        batteryBox.addView(topBatteryPercent);
        batteryBox.addView(topBatteryLabel);
        topStatus.addView(batteryBox, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams topStatusParams = new LinearLayout.LayoutParams(-1, -2);
        topStatusParams.setMargins(0, dp(2), 0, dp(8));
        content.addView(topStatus, topStatusParams);

        dateLine = tv("", 17, MUTED, Typeface.NORMAL);
        dateLine.setGravity(Gravity.CENTER);
        content.addView(dateLine, new LinearLayout.LayoutParams(-1, -2));
        hijriLine = tv("", 15, GOLD, Typeface.BOLD);
        hijriLine.setGravity(Gravity.CENTER);
        content.addView(hijriLine, new LinearLayout.LayoutParams(-1, -2));

        locationName = tv("📍 جاري تحديد الموقع", 14, GOLD, Typeface.BOLD);
        locationName.setGravity(Gravity.CENTER);
        locationName.setPadding(0, dp(7), 0, 0);
        content.addView(locationName, new LinearLayout.LayoutParams(-1, -2));

        Space s1 = new Space(this); content.addView(s1, new LinearLayout.LayoutParams(1, dp(18)));

        LinearLayout prayerCard = card("prayer_alpha", 60);
        prayerSectionTitle = tv("مواقيت الصلاة  ▾", 19, WHITE, Typeface.BOLD);
        prayerSectionTitle.setGravity(Gravity.RIGHT);
        prayerSectionTitle.setOnClickListener(v -> togglePrayerDetails());
        prayerCard.addView(prayerSectionTitle);

        prayerRow = new LinearLayout(this);
        prayerRow.setOrientation(LinearLayout.HORIZONTAL);
        prayerRow.setGravity(Gravity.CENTER);
        prayerRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        prayerRow.setOnClickListener(v -> togglePrayerDetails());
        prayerCard.addView(prayerRow, new LinearLayout.LayoutParams(-1, -2));

        // Everything except the six displayed times belongs to one collapsible group.
        prayerDetailsContainer = new LinearLayout(this);
        prayerDetailsContainer.setOrientation(LinearLayout.VERTICAL);
        nextPrayer = tv("", 18, GOLD, Typeface.BOLD);
        nextPrayer.setGravity(Gravity.CENTER);
        nextPrayer.setPadding(0, dp(14), 0, dp(3));
        prayerDetailsContainer.addView(nextPrayer);

        LinearLayout qiblaLine = new LinearLayout(this);
        qiblaLine.setOrientation(LinearLayout.HORIZONTAL);
        qiblaLine.setGravity(Gravity.CENTER_VERTICAL);
        qiblaLine.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        qiblaLine.setPadding(dp(2), dp(12), dp(2), 0);

        qiblaCompass = new QiblaCompassView(this);
        qiblaCompass.setContentDescription("بوصلة اتجاه القبلة: السهم الذهبي نحو الكعبة، والحرف N يحدد الشمال");
        qiblaLine.addView(qiblaCompass, new LinearLayout.LayoutParams(dp(120), dp(120)));

        LinearLayout qiblaDetails = new LinearLayout(this);
        qiblaDetails.setOrientation(LinearLayout.VERTICAL);
        qiblaDetails.setGravity(Gravity.CENTER_VERTICAL);
        qiblaDetails.setPadding(dp(10), 0, dp(4), 0);
        qiblaDegrees = tv("اتجاه القبلة", 15, GOLD, Typeface.BOLD);
        qiblaDegrees.setGravity(Gravity.RIGHT);
        qiblaDetails.addView(qiblaDegrees, new LinearLayout.LayoutParams(-1, -2));

        qiblaHeadingLabel = tv("أعلى الهاتف: جارٍ قراءة البوصلة", 12, MUTED, Typeface.NORMAL);
        qiblaHeadingLabel.setGravity(Gravity.RIGHT);
        qiblaDetails.addView(qiblaHeadingLabel, new LinearLayout.LayoutParams(-1, -2));

        qiblaStatus = tv("جارٍ تحديد اتجاه الهاتف...", 13, WHITE, Typeface.BOLD);
        qiblaStatus.setGravity(Gravity.RIGHT);
        qiblaStatus.setPadding(0, dp(7), 0, 0);
        qiblaDetails.addView(qiblaStatus, new LinearLayout.LayoutParams(-1, -2));

        TextView qiblaHint = tv("السهم الذهبي = القبلة • N = الشمال. أمسك الهاتف مستويًا.", 11, MUTED, Typeface.NORMAL);
        qiblaHint.setGravity(Gravity.RIGHT);
        qiblaHint.setPadding(0, dp(6), 0, 0);
        qiblaDetails.addView(qiblaHint, new LinearLayout.LayoutParams(-1, -2));

        TextView refreshQiblaLocation = pill("تحديث الموقع", false);
        refreshQiblaLocation.setOnClickListener(v -> {
            if (!LocationSupport.permitted(this)) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 7);
            } else {
                refreshLocation();
                Toast.makeText(this, "جارٍ تحديث الموقع؛ تحقق من اسم المدينة أعلى الشاشة",
                        Toast.LENGTH_SHORT).show();
            }
        });
        qiblaDetails.addView(refreshQiblaLocation,
                new LinearLayout.LayoutParams(-1, -2));
        qiblaLine.addView(qiblaDetails, new LinearLayout.LayoutParams(0, -2, 1f));
        prayerDetailsContainer.addView(qiblaLine, new LinearLayout.LayoutParams(-1, -2));
        prayerCard.addView(prayerDetailsContainer, new LinearLayout.LayoutParams(-1, -2));
        updatePrayerDetailsVisibility();
        updateQiblaDirection();

        content.addView(prayerCard, new LinearLayout.LayoutParams(-1, -2));

        Space s2 = new Space(this); content.addView(s2, new LinearLayout.LayoutParams(1, dp(14)));

        LinearLayout nCard = card("notification_alpha", 35);
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

        LinearLayout bottomActions = new LinearLayout(this);
        bottomActions.setOrientation(LinearLayout.HORIZONTAL);
        bottomActions.setGravity(Gravity.CENTER);
        bottomActions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bottomActions.setPadding(0, dp(4), 0, 0);

        TextView settings = pill("⚙ الإعدادات", false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bottomActions.addView(settings, new LinearLayout.LayoutParams(0, -2, 1));

        TextView unlock = pill("فتح الشاشة", true);
        unlock.setOnClickListener(v -> requestSecureExit());
        bottomActions.addView(unlock, new LinearLayout.LayoutParams(0, -2, 1));

        content.addView(bottomActions, new LinearLayout.LayoutParams(-1, -2));

        TextView note = tv("الإشعارات، الظهور عند القفل، تحديث الموقع والمظهر أصبحت داخل الإعدادات.", 11, MUTED, Typeface.NORMAL);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12), dp(10), dp(12), 0);
        content.addView(note);

        setContentView(page);
        applyBackground();
        updateLocationName();
    }

    private void updateBatteryStatus() {
        lastBatteryUpdate = System.currentTimeMillis();
        try {
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            if (topBatteryPercent != null) {
                topBatteryPercent.setText(pct >= 0 ? arabicDigits(String.valueOf(pct)) + "٪" : "--٪");
            }
            if (topBatteryIcon != null) topBatteryIcon.setText(charging ? "⚡🔋" : "🔋");
            if (topBatteryLabel != null) {
                topBatteryLabel.setText(charging ? "قيد الشحن" : "البطارية");
                topBatteryLabel.setTextColor(charging ? GOLD : MUTED);
            }
        } catch (Exception ignored) {}
    }

    private void loadCachedTopWeather() {
        try {
            SharedPreferences p = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
            if (!p.getBoolean("top_weather_has_data", false)) return;
            float temp = p.getFloat("top_weather_temp", 0f);
            int code = p.getInt("top_weather_code", 0);
            boolean isDay = p.getBoolean("top_weather_is_day", true);
            applyTopWeather(temp, code, isDay);
        } catch (Exception ignored) {}
    }

    private void refreshTopWeather(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastWeatherFetch < 15L * 60L * 1000L) return;
        lastWeatherFetch = now;

        final double qLat = lat;
        final double qLon = lon;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                String address = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + qLat
                        + "&longitude=" + qLon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&timezone=Asia%2FRiyadh";
                c = (HttpURLConnection) new URL(address).openConnection();
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "BAS-Lock/1.6");
                if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) out.append(line);
                reader.close();

                JSONObject current = new JSONObject(out.toString()).getJSONObject("current");
                float temp = (float) current.getDouble("temperature_2m");
                int code = current.getInt("weather_code");
                boolean isDay = current.optInt("is_day", 1) == 1;

                getSharedPreferences("bas_lock_settings", MODE_PRIVATE).edit()
                        .putBoolean("top_weather_has_data", true)
                        .putFloat("top_weather_temp", temp)
                        .putInt("top_weather_code", code)
                        .putBoolean("top_weather_is_day", isDay)
                        .apply();

                runOnUiThread(() -> applyTopWeather(temp, code, isDay));
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void applyTopWeather(float temp, int code, boolean isDay) {
        if (topWeatherTemp != null) {
            topWeatherTemp.setText(arabicDigits(String.valueOf(Math.round(temp))) + "°");
        }
        if (topWeatherIcon != null) topWeatherIcon.setText(weatherIcon(code, isDay));
        if (topWeatherCondition != null) topWeatherCondition.setText(weatherCondition(code));
    }

    private String weatherIcon(int code, boolean isDay) {
        if (code == 0) return isDay ? "☀️" : "🌙";
        if (code == 1 || code == 2) return isDay ? "🌤️" : "🌙☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 85 && code <= 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    private String weatherCondition(int code) {
        if (code == 0) return "صحو";
        if (code == 1) return "صحو غالبًا";
        if (code == 2) return "غائم جزئيًا";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 57) return "رذاذ";
        if (code >= 61 && code <= 67) return "أمطار";
        if (code >= 71 && code <= 77) return "ثلوج";
        if (code >= 80 && code <= 82) return "زخات";
        if (code >= 85 && code <= 86) return "زخات ثلجية";
        if (code >= 95) return "عواصف";
        return "غائم";
    }

    private void loadSavedLocation() {
        SharedPreferences p = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        // Existing prayer times can keep the legacy fallback, but the qibla
        // compass must not silently interpret Tabuk as the actual location.
        hasConfirmedQiblaLocation = false;
        if (p.contains("saved_lat_bits") && p.contains("saved_lon_bits")) {
            try {
                double savedLat = Double.longBitsToDouble(p.getLong("saved_lat_bits", 0L));
                double savedLon = Double.longBitsToDouble(p.getLong("saved_lon_bits", 0L));
                if (Double.isFinite(savedLat) && Double.isFinite(savedLon)
                        && Math.abs(savedLat) <= 90 && Math.abs(savedLon) <= 180
                        && !(savedLat == 0 && savedLon == 0)) {
                    lat = savedLat;
                    lon = savedLon;
                    hasConfirmedQiblaLocation = true;
                }
            } catch (Exception ignored) {}
        }
        if (locationName != null) updateLocationName();
        updateQiblaDirection();
    }

    private void saveLocation(double latitude, double longitude) {
        getSharedPreferences("bas_lock_settings", MODE_PRIVATE).edit()
                .putLong("saved_lat_bits", Double.doubleToRawLongBits(latitude))
                .putLong("saved_lon_bits", Double.doubleToRawLongBits(longitude))
                .apply();
    }

    private void applyImmersiveNavigationLock() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception ignored) {}
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
        if (!LocationSupport.permitted(this)) return;
        LocationSupport.refresh(this, fix -> {
            double latitude = fix.getLatitude(), longitude = fix.getLongitude();
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180
                    || (latitude == 0 && longitude == 0)) return;
            boolean moved = Math.abs(latitude - lat) >= 0.0001
                    || Math.abs(longitude - lon) >= 0.0001;
            lat = latitude;
            lon = longitude;
            hasConfirmedQiblaLocation = true;
            saveLocation(lat, lon);
            updateQiblaDirection();
            if (!moved) {
                updateLocationName();
                return;
            }
            lastWeatherFetch = 0L;
            refreshTopWeather(true);
            updateLocationName();
            updateQiblaDirection();
            safeRefreshAll();
        });
    }

    private void updateLocationName() {
        if (locationName == null) return;
        SharedPreferences p = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        locationName.setText(LocationSupport.label(p, lat, lon));
        if (LocationSupport.hasCachedName(p, lat, lon)) return;
        final double wantedLat = lat, wantedLon = lon;
        LocationSupport.resolveCity(this, wantedLat, wantedLon, name -> {
            if (isFinishing() || isDestroyed()) return;
            LocationSupport.saveCity(p, name, wantedLat, wantedLon);
            if (Math.abs(lat - wantedLat) < 0.0001 && Math.abs(lon - wantedLon) < 0.0001)
                locationName.setText(LocationSupport.label(p, lat, lon));
        });
    }

    private void updateQiblaDirection() {
        qiblaBearing = hasConfirmedQiblaLocation
                ? LocationSupport.bearingToMakkah(lat, lon) : Double.NaN;
        if (qiblaDegrees != null) {
            if (!hasConfirmedQiblaLocation) {
                qiblaDegrees.setText("القبلة: يلزم تحديد الموقع");
            } else {
                String value = arabicDigits(String.valueOf(Math.round(qiblaBearing)));
                String label = LocationSupport.label(
                        getSharedPreferences("bas_lock_settings", MODE_PRIVATE), lat, lon)
                        .replace("📍", "").trim();
                qiblaDegrees.setText("القبلة: " + value
                        + "° من الشمال الحقيقي\nحسب الموقع: " + label);
            }
        }
        updateQiblaCompass();
    }

    private void startCompass() {
        if (qiblaCompass == null) return;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        currentTrueHeading = Double.NaN;
        compassTilted = false;
        updateQiblaCompass();
        if (rotationSensor == null) {
            qiblaCompass.setDirections(qiblaBearing, Double.NaN);
            if (qiblaStatus != null)
                qiblaStatus.setText("لا توجد بوصلة في الجهاز؛ استخدم زاوية القبلة من الشمال");
            return;
        }
        if (qiblaStatus != null)
            qiblaStatus.setText("أمسك الهاتف مستويًا، وجّه أعلاه نحو السهم الذهبي");
        if (!sensorManager.registerListener(compassListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                && qiblaStatus != null)
            qiblaStatus.setText("تعذر تشغيل البوصلة؛ زاوية القبلة من الشمال موضحة أعلاه");
    }

    private void updateQiblaCompass() {
        if (qiblaCompass != null)
            qiblaCompass.setDirections(qiblaBearing, currentTrueHeading);
        if (qiblaHeadingLabel != null) {
            if (Double.isNaN(currentTrueHeading)) {
                qiblaHeadingLabel.setText("أعلى الهاتف: لا تتوفر قراءة موثوقة");
            } else {
                qiblaHeadingLabel.setText("أعلى الهاتف: "
                        + arabicDigits(String.valueOf(Math.round(currentTrueHeading)))
                        + "° من الشمال");
            }
        }
        if (qiblaStatus == null) return;
        if (!hasConfirmedQiblaLocation) {
            qiblaStatus.setText("⚠ الموقع غير محدد؛ اضغط تحديث الموقع");
            return;
        }
        if (compassTilted) {
            qiblaStatus.setText("أمسك الهاتف أفقيًا والشاشة للأعلى");
            return;
        }
        if (Double.isNaN(currentTrueHeading)) return;

        double turn = ((qiblaBearing - currentTrueHeading + 540.0) % 360.0) - 180.0;
        long degrees = Math.round(Math.abs(turn));
        String instruction;
        if (degrees <= 5) {
            instruction = "✓ أعلى الهاتف مواجه للقبلة";
        } else if (turn > 0) {
            instruction = "أدر أعلى الهاتف يمينًا " + arabicDigits(String.valueOf(degrees)) + "°";
        } else {
            instruction = "أدر أعلى الهاتف يسارًا " + arabicDigits(String.valueOf(degrees)) + "°";
        }
        if (!instruction.contentEquals(qiblaStatus.getText()))
            qiblaStatus.setText(instruction);
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

    private void togglePrayerDetails() {
        prayerDetailsExpanded = !prayerDetailsExpanded;
        updatePrayerDetailsVisibility();
        if (prayerDetailsExpanded) {
            updateQiblaDirection();
            startCompass();
        } else {
            if (sensorManager != null) sensorManager.unregisterListener(compassListener);
            currentTrueHeading = Double.NaN;
        }
    }

    private void updatePrayerDetailsVisibility() {
        if (prayerDetailsContainer != null) {
            prayerDetailsContainer.setVisibility(prayerDetailsExpanded ? View.VISIBLE : View.GONE);
        }
        if (prayerSectionTitle != null) {
            prayerSectionTitle.setText(prayerDetailsExpanded ? "مواقيت الصلاة  ▴" : "مواقيت الصلاة  ▾");
            prayerSectionTitle.setContentDescription(prayerDetailsExpanded
                    ? "مواقيت الصلاة، اضغط لإخفاء الصلاة القادمة والقبلة وتحديث الموقع"
                    : "مواقيت الصلاة، اضغط لعرض الصلاة القادمة والقبلة وتحديث الموقع");
        }
        if (prayerRow != null) {
            prayerRow.setContentDescription(prayerDetailsExpanded
                    ? "اضغط على مواقيت الصلاة لإخفاء التفاصيل"
                    : "اضغط على مواقيت الصلاة لإظهار التفاصيل");
        }
    }

    private void renderPrayerTimes() {
        prayerRow.removeAllViews();
        Map<String, ZonedDateTime> map = times.asMap();
        ZonedDateTime now = ZonedDateTime.now();
        String next = getNextPrayerName(now);
        for (Map.Entry<String, ZonedDateTime> e : map.entrySet()) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            int color = e.getKey().equals(next) ? GOLD : WHITE;
            TextView name = tv(e.getKey(), 12, color, Typeface.BOLD); name.setGravity(Gravity.CENTER);
            TextView t = tv(formatTime(e.getValue()), 13, color, Typeface.NORMAL); t.setGravity(Gravity.CENTER);
            cell.addView(name); cell.addView(t);
            prayerRow.addView(cell, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
    }

    private void updateClockAndCountdown() {
        ZonedDateTime now = ZonedDateTime.now();
        clock.setText(formatTime(now));
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
        notificationSummary.setText("مهم " + arabicDigits(String.valueOf(important)) +
                "  •  مراجعة " + arabicDigits(String.valueOf(review)) +
                "  •  أخرى " + arabicDigits(String.valueOf(other)));

        if (items.isEmpty()) {
            TextView none = tv("لا توجد إشعارات محفوظة. فعّل صلاحية الوصول إلى الإشعارات من الزر أدناه.", 13, MUTED, Typeface.NORMAL);
            none.setGravity(Gravity.CENTER);
            none.setPadding(dp(8), dp(16), dp(8), dp(8));
            notificationsBox.addView(none);
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isDeviceLocked();
        boolean showUnlockedText = getSharedPreferences("bas_lock_settings", MODE_PRIVATE)
                .getBoolean("show_text_unlocked", true);

        LinkedHashMap<String, List<NotificationStore.Item>> groups = new LinkedHashMap<>();
        for (NotificationStore.Item x : items) {
            groups.computeIfAbsent(x.pkg, k -> new ArrayList<>()).add(x);
        }

        for (Map.Entry<String, List<NotificationStore.Item>> entry : groups.entrySet()) {
            String pkg = entry.getKey();
            List<NotificationStore.Item> groupItems = entry.getValue();
            NotificationStore.Item newest = groupItems.get(0);
            boolean expanded = expandedApps.contains(pkg);

            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
            gp.setMargins(0, dp(8), 0, 0);
            group.setLayoutParams(gp);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            header.setPadding(dp(11), dp(9), dp(11), dp(9));

            GradientDrawable hg = new GradientDrawable();
            hg.setCornerRadius(dp(16));
            hg.setColor(alphaColor(Color.WHITE, prefPercent("notification_alpha", 35)));
            hg.setStroke(dp(1), newest.level >= 3 ? GOLD : Color.argb(80,255,255,255));
            header.setBackground(hg);

            ImageView icon = new ImageView(this);
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                icon.setImageDrawable(d);
            } catch (Exception ignored) {}
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(38), dp(38));
            ip.setMargins(dp(8), 0, 0, 0);
            header.addView(icon, ip);

            LinearLayout titleBox = new LinearLayout(this);
            titleBox.setOrientation(LinearLayout.VERTICAL);
            TextView appName = tv(TextUtils.isEmpty(newest.app) ? pkg : newest.app,
                    15, newest.level >= 3 ? GOLD : WHITE, Typeface.BOLD);
            appName.setGravity(Gravity.RIGHT);
            titleBox.addView(appName);

            String countText = groupItems.size() == 1
                    ? "إشعار واحد"
                    : arabicDigits(String.valueOf(groupItems.size())) + " إشعارات";
            TextView count = tv(countText + (expanded ? "  ▲" : "  ▼"), 12, MUTED, Typeface.NORMAL);
            count.setGravity(Gravity.RIGHT);
            titleBox.addView(count);
            header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

            TextView clearAll = pill("مسح الكل", false);
            clearAll.setTextSize(10);
            clearAll.setOnClickListener(v -> dismissPackage(pkg));
            header.addView(clearAll);

            header.setOnClickListener(v -> {
                if (expandedApps.contains(pkg)) expandedApps.remove(pkg);
                else expandedApps.add(pkg);
                refreshNotifications();
            });
            attachSwipeDismiss(header, () -> dismissPackage(pkg));
            group.addView(header);

            if (expanded) {
                for (NotificationStore.Item x : groupItems) {
                    LinearLayout row = buildNotificationRow(x, locked, showUnlockedText);
                    attachSwipeDismiss(row, () -> dismissNotification(x.key));
                    group.addView(row);
                }
            }

            notificationsBox.addView(group);
        }

        TextView hint = tv("اضغط على التطبيق لعرض إشعاراته. اسحب إشعارًا لإغلاقه، أو اسحب مجموعة التطبيق لإغلاق جميع إشعاراته.", 11, MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(8), dp(12), dp(8), dp(3));
        notificationsBox.addView(hint);
    }

    private LinearLayout buildNotificationRow(NotificationStore.Item x, boolean locked, boolean showUnlockedText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));

        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(14));
        int alpha = Math.max(18, prefPercent("notification_alpha", 35) - 8);
        gd.setColor(alphaColor(Color.WHITE, alpha));
        gd.setStroke(dp(1), x.level >= 3 ? GOLD : Color.argb(55,255,255,255));
        row.setBackground(gd);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(dp(12), dp(6), dp(12), 0);
        row.setLayoutParams(rp);

        String summary;
        if (locked || !showUnlockedText) {
            summary = TextUtils.isEmpty(x.title) ? "إشعار جديد" : x.title;
        } else {
            summary = TextUtils.isEmpty(x.text) ? x.title :
                    (TextUtils.isEmpty(x.title) ? x.text : x.title + " — " + x.text);
        }

        TextView body = tv(summary, 13, WHITE, Typeface.NORMAL);
        body.setGravity(Gravity.RIGHT);
        body.setMaxLines(3);
        row.addView(body);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.RIGHT);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        controls.setPadding(0, dp(6), 0, 0);

        TextView manage = pill("إعدادات الإشعارات", false);
        manage.setTextSize(10);
        manage.setOnClickListener(v -> openAppNotificationSettings(x.pkg));
        controls.addView(manage);

        TextView closeOne = pill("إغلاق", false);
        closeOne.setTextSize(10);
        closeOne.setOnClickListener(v -> dismissNotification(x.key));
        controls.addView(closeOne);

        row.addView(controls);

        row.setOnClickListener(v -> {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(x.pkg);
                if (launch != null) startActivity(launch);
            } catch (Exception ignored) {}
        });

        return row;
    }

    private void dismissNotification(String key) {
        NotificationStore.remove(this, key);
        Intent i = new Intent("com.bas.lock.DISMISS_KEY")
                .setPackage(getPackageName())
                .putExtra("key", key);
        sendBroadcast(i);
        refreshNotifications();
    }

    private void dismissPackage(String pkg) {
        NotificationStore.removePackage(this, pkg);
        expandedApps.remove(pkg);
        Intent i = new Intent("com.bas.lock.DISMISS_PACKAGE")
                .setPackage(getPackageName())
                .putExtra("pkg", pkg);
        sendBroadcast(i);
        refreshNotifications();
    }

    private void attachSwipeDismiss(View view, Runnable onDismiss) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    v.setTranslationX(0f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (Math.abs(dx) > Math.abs(dy)) {
                        v.setTranslationX(dx * 0.65f);
                        v.setAlpha(Math.max(0.35f, 1f - Math.abs(dx) / Math.max(1f, v.getWidth())));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    float totalDx = event.getRawX() - downX[0];
                    float totalDy = event.getRawY() - downY[0];
                    if (Math.abs(totalDx) > dp(90) && Math.abs(totalDx) > Math.abs(totalDy) * 1.2f) {
                        v.animate().translationX(totalDx > 0 ? v.getWidth() : -v.getWidth())
                                .alpha(0f).setDuration(140).withEndAction(onDismiss).start();
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(120).start();
                        if (Math.abs(totalDx) < dp(12) && Math.abs(totalDy) < dp(12)) v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.animate().translationX(0f).alpha(1f).setDuration(120).start();
                    return true;
            }
            return false;
        });
    }

    private void openClockApp() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            try {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { launchClockApp(); }
                    @Override public void onDismissError() {
                        Toast.makeText(MainActivity.this, "افتح قفل الجهاز لفتح الساعة", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        launchClockApp();
    }

    private void launchClockApp() {
        String[] preferred = {"com.sec.android.app.clockpackage", "com.google.android.deskclock"};
        for (String pkg : preferred) {
            try {
                Intent alarm = new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
                alarm.setPackage(pkg);
                alarm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(alarm);
                return;
            } catch (Exception ignored) {}
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(launch);
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            Intent generic = new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS);
            generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(generic);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر العثور على تطبيق الساعة", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean use24Hour() {
        return getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getBoolean("use_24h", true);
    }

    private String formatTime(ZonedDateTime value) {
        if (use24Hour()) {
            return arabicDigits(String.format(Locale.US, "%02d:%02d", value.getHour(), value.getMinute()));
        }
        int hour = value.getHour();
        String suffix = hour < 12 ? "ص" : "م";
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        return arabicDigits(String.format(Locale.US, "%d:%02d", h12, value.getMinute())) + " " + suffix;
    }

    private void openBasPlatformSecurely() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            try {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() {
                        launchBasPlatform();
                    }
                    @Override public void onDismissError() {
                        Toast.makeText(MainActivity.this, "يجب فتح قفل الجهاز أولًا", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            } catch (Exception ignored) {}
        }
        launchBasPlatform();
    }

    private void launchBasPlatform() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage("com.drburghash.basfinal");
            if (launch == null) {
                Toast.makeText(this, "تعذر العثور على BAS Platform مثبتًا على الجهاز", Toast.LENGTH_LONG).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launch);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح BAS Platform", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyBackground() {
        if (wallpaper == null || backgroundShade == null) return;
        SharedPreferences prefs = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        String saved = prefs.getString("background_uri", "");
        int brightness = Math.max(0, Math.min(100, prefs.getInt("background_brightness", 50)));
        boolean hasImage = !TextUtils.isEmpty(saved);
        if (hasImage) {
            try {
                wallpaper.setImageURI(Uri.parse(saved));
                if (wallpaper.getDrawable() == null) hasImage = false;
            } catch (Exception e) {
                hasImage = false;
            }
        }
        if (hasImage) {
            wallpaper.setVisibility(View.VISIBLE);
            // At 50%, retain the prior image dimming (145/255). 100% is unshaded.
            int opacity = Math.min(245, Math.round((100 - brightness) * 2.9f));
            backgroundShade.setBackgroundColor(Color.argb(opacity, 2, 14, 30));
        } else {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            // The default gradient can be dimmed or lightened separately.
            if (brightness < 50)
                backgroundShade.setBackgroundColor(Color.argb((50 - brightness) * 4, 0, 0, 0));
            else if (brightness > 50)
                backgroundShade.setBackgroundColor(Color.argb((brightness - 50) * 2, 255, 255, 255));
            else
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
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
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

    private LinearLayout card(String alphaKey, int defaultPercent) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(alphaColor(Color.rgb(5, 27, 50), prefPercent(alphaKey, defaultPercent)));
        g.setStroke(dp(1), Color.argb(90, 228, 184, 95));
        l.setBackground(g);
        return l;
    }

    private int prefPercent(String key, int def) {
        return Math.max(0, Math.min(100,
                getSharedPreferences("bas_lock_settings", MODE_PRIVATE).getInt(key, def)));
    }

    private int alphaColor(int color, int percent) {
        int alpha = Math.round(255f * Math.max(0, Math.min(100, percent)) / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private TextView pill(String text, boolean gold) {
        TextView v = tv(text, 13, WHITE, Typeface.BOLD); v.setGravity(Gravity.CENTER); v.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        int buttonAlpha = prefPercent("button_alpha", 28);
        g.setColor(gold ? alphaColor(GOLD, buttonAlpha) : alphaColor(Color.WHITE, buttonAlpha));
        g.setStroke(dp(1), gold ? GOLD : Color.argb(70,255,255,255));
        v.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(dp(5),0,dp(5),0); v.setLayoutParams(p); return v;
    }

    private int userTextColor() {
        String raw = getSharedPreferences("bas_lock_settings", MODE_PRIVATE)
                .getString("text_color", "#FFFDF8");
        try { return Color.parseColor(raw); }
        catch (Exception e) { return WHITE; }
    }

    private TextView tv(String s, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        int actual = (color == WHITE || color == MUTED) ? userTextColor() : color;
        v.setTextColor(actual);
        v.setTypeface(Typeface.create("sans", style));
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String arabicDigits(String s) {
        char[] a = {'٠','١','٢','٣','٤','٥','٦','٧','٨','٩'}; StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? a[c-'0'] : c); return b.toString();
    }
}
