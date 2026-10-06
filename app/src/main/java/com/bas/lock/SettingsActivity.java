package com.bas.lock;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public class SettingsActivity extends Activity {
    private static final int REQ_BACKGROUND = 51;
    private SharedPreferences prefs;
    private LinearLayout root;
    private static final int NAVY = Color.rgb(6,26,51), GOLD = Color.rgb(228,184,95), WHITE = Color.rgb(255,253,248), MUTED = Color.rgb(182,199,217);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("bas_lock_settings", MODE_PRIVATE);
        applyImmersiveNavigationLock();
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        applyImmersiveNavigationLock();
        if (prefs != null) build();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersiveNavigationLock();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(3,15,31), Color.rgb(10,36,68), Color.rgb(7,76,88)});
        scroll.setBackground(bg);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(34), dp(18), dp(30)); scroll.addView(root);

        TextView title = tv("إعدادات BAS Lock", 25, WHITE, Typeface.BOLD); title.setGravity(Gravity.RIGHT); root.addView(title);
        TextView sub = tv("تصحيح المواقيت والخصوصية", 13, MUTED, Typeface.NORMAL); sub.setPadding(0,0,0,dp(18)); root.addView(sub);

        root.addView(sectionTitle("تصحيح مواقيت الصلاة بالدقائق"));
        addOffset("الفجر", "offset_fajr");
        addOffset("الشروق", "offset_sunrise");
        addOffset("الظهر", "offset_dhuhr");
        addOffset("العصر", "offset_asr");
        addOffset("المغرب", "offset_maghrib");
        addOffset("العشاء", "offset_isha");

        TextView reset = button("إعادة جميع التصحيحات إلى 0");
        reset.setOnClickListener(v -> {
            prefs.edit().putInt("offset_fajr",0).putInt("offset_sunrise",0).putInt("offset_dhuhr",0)
                    .putInt("offset_asr",0).putInt("offset_maghrib",0).putInt("offset_isha",0).apply();
            build();
        });
        root.addView(reset);

        root.addView(space(18));
        root.addView(sectionTitle("المظهر"));
        addTimeFormatControl();
        root.addView(space(12));
        addTextColorControl();
        root.addView(space(12));
        root.addView(sectionTitle("الشفافية"));
        addTransparencyControl("مواقيت الصلاة", "prayer_alpha", 60);
        addTransparencyControl("الإشعارات", "notification_alpha", 35);
        addTransparencyControl("الأزرار", "button_alpha", 28);
        root.addView(space(10));
        addBackgroundControls();

        root.addView(space(18));
        root.addView(sectionTitle("الخصوصية والسلوك"));

        addPermissionCard();
        addLocationControl();

        addToggle("الظهور التلقائي عند إضاءة شاشة القفل", "auto_show", true,
                "لن يعمل تلقائيًا إلا بعد منح الوصول إلى الإشعارات و«الظهور فوق التطبيقات».");
        addToggle("إظهار نص الإشعار بعد فتح الجهاز", "show_text_unlocked", true,
                "أثناء قفل الجهاز لا يعرض BAS Lock نص الإشعار الكامل.");

        TextView done = button("تم"); done.setTextColor(NAVY);
        GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(18)); gd.setColor(GOLD); done.setBackground(gd);
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(-1, -2);
        doneParams.setMargins(0, dp(24), 0, 0); done.setLayoutParams(doneParams);
        root.addView(done);

        setContentView(scroll);
    }



    private void addTimeFormatControl() {
        LinearLayout box = cardRow();
        TextView label = tv("نظام عرض الساعة", 16, WHITE, Typeface.BOLD);
        box.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        boolean use24 = prefs.getBoolean("use_24h", true);
        TextView state = smallButton(use24 ? "٢٤ ساعة" : "١٢ ساعة");
        state.setOnClickListener(v -> {
            boolean next = !prefs.getBoolean("use_24h", true);
            prefs.edit().putBoolean("use_24h", next).apply();
            state.setText(next ? "٢٤ ساعة" : "١٢ ساعة");
            Toast.makeText(this, "سيطبق النظام على الساعة ومواقيت الصلاة", Toast.LENGTH_SHORT).show();
        });
        box.addView(state);
        root.addView(box);

        TextView note = tv("يتغير تنسيق الساعة الرئيسية وجميع مواقيت الصلاة معًا. نظام ١٢ ساعة يعرض ص/م.", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0, dp(6), 0, 0);
        root.addView(note);
    }

    private void addTextColorControl() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(Color.argb(55,255,255,255));
        box.setBackground(bg);

        TextView title = tv("لون النص", 15, WHITE, Typeface.BOLD);
        box.addView(title);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        presets.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        presets.setPadding(0, dp(8), 0, dp(4));

        addColorPreset(presets, "أبيض", "#FFFDF8");
        addColorPreset(presets, "ذهبي", "#E4B85F");
        addColorPreset(presets, "أسود", "#111111");
        addColorPreset(presets, "سماوي", "#8FE8FF");
        box.addView(presets);

        LinearLayout custom = new LinearLayout(this);
        custom.setOrientation(LinearLayout.HORIZONTAL);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        custom.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        EditText hex = new EditText(this);
        hex.setHint("#FFFFFF");
        hex.setSingleLine(true);
        hex.setText(prefs.getString("text_color", "#FFFDF8"));
        hex.setTextColor(WHITE);
        hex.setHintTextColor(MUTED);
        custom.addView(hex, new LinearLayout.LayoutParams(0, -2, 1));

        TextView apply = smallButton("تطبيق");
        apply.setOnClickListener(v -> {
            String raw = hex.getText().toString().trim();
            if (!raw.startsWith("#")) raw = "#" + raw;
            try {
                Color.parseColor(raw);
                prefs.edit().putString("text_color", raw.toUpperCase()).apply();
                Toast.makeText(this, "تم حفظ لون النص", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "اكتب اللون مثل #FFFFFF", Toast.LENGTH_SHORT).show();
            }
        });
        custom.addView(apply);
        box.addView(custom);

        TextView note = tv("يطبق على الساعة والتاريخ ومواقيت الصلاة والنصوص والأزرار الأساسية. اللون الذهبي المميز يبقى للتنبيه والصلاة القادمة.", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);

        root.addView(box);
    }

    private void addColorPreset(LinearLayout parent, String label, String hex) {
        TextView b = smallButton(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMargins(dp(3),0,dp(3),0);
        b.setLayoutParams(p);
        b.setOnClickListener(v -> {
            prefs.edit().putString("text_color", hex).apply();
            Toast.makeText(this, "تم اختيار " + label, Toast.LENGTH_SHORT).show();
            build();
        });
        parent.addView(b);
    }

    private void addTransparencyControl(String title, String key, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(9));

        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(15));
        g.setColor(Color.argb(55,255,255,255));
        box.setBackground(g);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView label = tv(title, 14, WHITE, Typeface.BOLD);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        int current = prefs.getInt(key, def);
        TextView value = tv(arabicDigits(current) + "%", 13, GOLD, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(55));
        top.addView(value);
        box.addView(top);

        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(current);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(key, progress).apply();
                value.setText(arabicDigits(progress) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(seek, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(6), 0, 0);
        box.setLayoutParams(p);
        root.addView(box);
    }

    private String arabicDigits(int n) {
        return String.valueOf(n)
                .replace('0','٠').replace('1','١').replace('2','٢').replace('3','٣')
                .replace('4','٤').replace('5','٥').replace('6','٦').replace('7','٧')
                .replace('8','٨').replace('9','٩');
    }

    private void addBackgroundControls() {
        TextView choose = button("اختيار خلفية من الصور");
        choose.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_BACKGROUND);
            } catch (Exception e) {
                Toast.makeText(this, "تعذر فتح الصور", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(choose);

        TextView resetBg = button("استعادة الخلفية الافتراضية");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1,-2);
        rp.setMargins(0,dp(7),0,0);
        resetBg.setLayoutParams(rp);
        resetBg.setOnClickListener(v -> {
            prefs.edit().remove("background_uri").apply();
            Toast.makeText(this, "تمت استعادة الخلفية الافتراضية", Toast.LENGTH_SHORT).show();
        });
        root.addView(resetBg);

        TextView note = tv("يمكن اختيار أي صورة من الجهاز. يضيف BAS Lock تعتيماً خفيفاً فوقها حتى تبقى الساعة ومواقيت الصلاة والإشعارات واضحة.", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0,dp(7),0,0);
        root.addView(note);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BACKGROUND && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.edit().putString("background_uri", uri.toString()).apply();
            Toast.makeText(this, "تم حفظ الخلفية", Toast.LENGTH_SHORT).show();
        }
    }

    private void addLocationControl() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(Color.argb(65,255,255,255));
        box.setBackground(g);

        TextView title = tv("الموقع ومواقيت الصلاة", 14, WHITE, Typeface.BOLD);
        box.addView(title);

        TextView update = button("تحديث الموقع الآن");
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1,-2);
        up.setMargins(0,dp(8),0,0);
        update.setLayoutParams(up);
        update.setOnClickListener(v -> refreshLocationFromSettings());
        box.addView(update);

        TextView note = tv("يحفظ BAS Lock آخر موقع متاح ويستخدمه لحساب مواقيت الصلاة. إذا تعذر الحصول على الموقع يستخدم تبوك افتراضيًا.", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0,dp(7),0,0);
        box.addView(note);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(7),0,dp(7));
        box.setLayoutParams(p);
        root.addView(box);
    }

    private void refreshLocationFromSettings() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 77);
            return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location x = lm.getLastKnownLocation(provider);
                if (x != null && (best == null || x.getAccuracy() < best.getAccuracy())) best = x;
            }
            if (best != null) {
                prefs.edit()
                        .putLong("saved_lat_bits", Double.doubleToRawLongBits(best.getLatitude()))
                        .putLong("saved_lon_bits", Double.doubleToRawLongBits(best.getLongitude()))
                        .apply();
                Toast.makeText(this, "تم تحديث الموقع", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "لا يوجد موقع حديث. افتح الموقع في الجهاز وحاول مرة أخرى.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "تعذر تحديث الموقع", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 77 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshLocationFromSettings();
        }
    }

    private void applyImmersiveNavigationLock() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception ignored) {}
    }

    private void addPermissionCard() {
        boolean notif = hasNotificationAccess();
        boolean overlay = Settings.canDrawOverlays(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12),dp(12),dp(12),dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(Color.argb(65,255,255,255));
        box.setBackground(g);

        TextView title = tv("جاهزية الظهور على شاشة القفل", 14, WHITE, Typeface.BOLD);
        box.addView(title);

        TextView status = tv(
                "الوصول إلى الإشعارات: " + (notif ? "مفعّل ✓" : "غير مفعّل") +
                "\nالظهور فوق التطبيقات: " + (overlay ? "مفعّل ✓" : "غير مفعّل"),
                12, (notif && overlay) ? GOLD : MUTED, Typeface.NORMAL);
        status.setPadding(0,dp(7),0,dp(9));
        box.addView(status);

        TextView notifBtn = button(notif ? "الوصول إلى الإشعارات ✓" : "تفعيل الوصول إلى الإشعارات");
        notifBtn.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) {}
        });
        box.addView(notifBtn);

        TextView overlayBtn = button(overlay ? "الظهور فوق التطبيقات ✓" : "السماح بالظهور فوق التطبيقات");
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1,-2);
        op.setMargins(0,dp(7),0,0);
        overlayBtn.setLayoutParams(op);
        overlayBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        });
        box.addView(overlayBtn);

        TextView note = tv("يحتاج BAS Lock هذين الإذنين حتى يستطيع الاستجابة عند إضاءة شاشة القفل ومحاولة إظهار اللوحة فوق القفل.", 11, MUTED, Typeface.NORMAL);
        note.setPadding(0,dp(8),0,0);
        box.addView(note);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(0,dp(7),0,dp(7));
        box.setLayoutParams(p);
        root.addView(box);
    }

    private boolean hasNotificationAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void addOffset(String name, String key) {
        LinearLayout row = cardRow();
        TextView label = tv(name, 16, WHITE, Typeface.BOLD); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView minus = smallButton("−");
        TextView value = tv(signed(prefs.getInt(key,0)), 16, GOLD, Typeface.BOLD); value.setGravity(Gravity.CENTER); value.setMinWidth(dp(52));
        TextView plus = smallButton("+");
        minus.setOnClickListener(v -> { int n=Math.max(-30,prefs.getInt(key,0)-1); prefs.edit().putInt(key,n).apply(); value.setText(signed(n)); });
        plus.setOnClickListener(v -> { int n=Math.min(30,prefs.getInt(key,0)+1); prefs.edit().putInt(key,n).apply(); value.setText(signed(n)); });
        row.addView(minus); row.addView(value); row.addView(plus); root.addView(row);
    }

    private void addToggle(String title, String key, boolean def, String note) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12),dp(11),dp(12),dp(11));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(16)); g.setColor(Color.argb(65,255,255,255)); box.setBackground(g);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView t = tv(title, 14, WHITE, Typeface.BOLD); row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        TextView state = smallButton(prefs.getBoolean(key,def)?"مفعّل":"موقوف");
        state.setOnClickListener(v->{ boolean n=!prefs.getBoolean(key,def); prefs.edit().putBoolean(key,n).apply(); state.setText(n?"مفعّل":"موقوف"); });
        row.addView(state); box.addView(row);
        TextView n = tv(note, 11, MUTED, Typeface.NORMAL); n.setPadding(0,dp(5),0,0); box.addView(n);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(7),0,0); box.setLayoutParams(p); root.addView(box);
    }

    private LinearLayout cardRow() {
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        r.setPadding(dp(12),dp(8),dp(12),dp(8));
        GradientDrawable g=new GradientDrawable(); g.setCornerRadius(dp(15)); g.setColor(Color.argb(55,255,255,255)); r.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(6),0,0); r.setLayoutParams(p); return r;
    }

    private TextView sectionTitle(String s) { TextView v=tv(s,16,GOLD,Typeface.BOLD); v.setPadding(0,dp(6),0,dp(6)); return v; }
    private TextView smallButton(String s) { TextView v=tv(s,14,WHITE,Typeface.BOLD); v.setGravity(Gravity.CENTER); v.setPadding(dp(12),dp(7),dp(12),dp(7)); GradientDrawable g=new GradientDrawable(); g.setCornerRadius(dp(14)); g.setStroke(dp(1),Color.argb(90,228,184,95)); g.setColor(Color.argb(50,255,255,255)); v.setBackground(g); return v; }
    private TextView button(String s) { TextView v=tv(s,14,WHITE,Typeface.BOLD); v.setGravity(Gravity.CENTER); v.setPadding(dp(12),dp(11),dp(12),dp(11)); GradientDrawable g=new GradientDrawable(); g.setCornerRadius(dp(18)); g.setStroke(dp(1),Color.argb(90,228,184,95)); g.setColor(Color.argb(55,255,255,255)); v.setBackground(g); return v; }
    private TextView tv(String s,int sp,int color,int style){ TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(color); v.setTypeface(Typeface.create("sans",style)); v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL); return v; }
    private View space(int h){ Space s=new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h))); return s; }
    private String signed(int n){ return n==0?"0":(n>0?"+":"")+n; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
