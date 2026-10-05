package com.bas.lock;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class NotificationStore {
    public static final class Item {
        public String key, pkg, app, title, text;
        public long when;
        public int level;
    }

    private static final String PREF = "bas_lock_notifications";
    private static final String DATA = "items";
    private static final int LIMIT = 40;

    private NotificationStore() {}

    public static synchronized void upsert(Context c, Item item) {
        List<Item> items = load(c);
        items.removeIf(x -> item.key.equals(x.key));
        items.add(item);
        items.sort(Comparator.comparingLong((Item x) -> x.when).reversed());
        if (items.size() > LIMIT) items = new ArrayList<>(items.subList(0, LIMIT));
        save(c, items);
    }

    public static synchronized void remove(Context c, String key) {
        List<Item> items = load(c);
        items.removeIf(x -> key.equals(x.key));
        save(c, items);
    }

    public static synchronized List<Item> load(Context c) {
        ArrayList<Item> out = new ArrayList<>();
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = p.getString(DATA, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item x = new Item();
                x.key = o.optString("key"); x.pkg = o.optString("pkg"); x.app = o.optString("app");
                x.title = o.optString("title"); x.text = o.optString("text");
                x.when = o.optLong("when"); x.level = o.optInt("level", 1);
                out.add(x);
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparingLong((Item x) -> x.when).reversed());
        return out;
    }

    public static synchronized void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(DATA).apply();
    }

    private static void save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item x : items) {
                JSONObject o = new JSONObject();
                o.put("key", x.key); o.put("pkg", x.pkg); o.put("app", x.app);
                o.put("title", x.title); o.put("text", x.text); o.put("when", x.when); o.put("level", x.level);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(DATA, arr.toString()).apply();
    }
}
