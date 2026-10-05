package com.bas.lock;

import android.app.Activity;
import android.content.SharedPreferences;
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
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null) build();
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
        addBackgroundControls();

        root.addView(space(18));
        root.addView(sectionTitle("الخصوصية والسلوك"));

        addPermissionCard();

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
