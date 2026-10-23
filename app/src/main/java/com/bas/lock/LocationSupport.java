package com.bas.lock;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Location names, fresh location requests and great-circle Qibla bearing. */
public final class LocationSupport {
    private static final double MAKKAH_LAT = 21.422487;
    private static final double MAKKAH_LON = 39.826206;
    private LocationSupport() {}

    public static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static void store(SharedPreferences prefs, Location location) {
        prefs.edit()
                .putLong("saved_lat_bits", Double.doubleToRawLongBits(location.getLatitude()))
                .putLong("saved_lon_bits", Double.doubleToRawLongBits(location.getLongitude()))
                .apply();
    }

    /** Delivers the newest cached fix first, then a newly acquired fix if possible. */
    public static void refresh(Activity activity, Consumer<Location> callback) {
        if (!permitted(activity)) return;
        LocationManager lm = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        Location newest = null;
        try {
            for (String provider : lm.getProviders(true)) {
                try {
                    Location candidate = lm.getLastKnownLocation(provider);
                    if (candidate != null && (newest == null || candidate.getTime() > newest.getTime()))
                        newest = candidate;
                } catch (SecurityException ignored) {}
            }
        } catch (Exception ignored) {}
        // Do not silently substitute a stale fix from a different city.
        if (newest != null && newest.getTime() > 0
                && Math.abs(System.currentTimeMillis() - newest.getTime()) <= 48L * 60 * 60 * 1000)
            callback.accept(newest);

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        ? LocationManager.NETWORK_PROVIDER
                        : (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null);
                if (provider != null) {
                    lm.getCurrentLocation(provider, null, activity.getMainExecutor(), fix -> {
                        if (fix != null && !activity.isFinishing() && !activity.isDestroyed()) callback.accept(fix);
                    });
                }
            } catch (Exception ignored) {}
        }
    }

    public static double bearingToMakkah(double latitude, double longitude) {
        double a = Math.toRadians(latitude);
        double b = Math.toRadians(MAKKAH_LAT);
        double dl = Math.toRadians(MAKKAH_LON - longitude);
        double y = Math.sin(dl) * Math.cos(b);
        double x = Math.cos(a) * Math.sin(b) - Math.sin(a) * Math.cos(b) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    public static boolean hasCachedName(SharedPreferences prefs, double latitude, double longitude) {
        if (TextUtils.isEmpty(prefs.getString("saved_city_name", ""))) return false;
        if (!prefs.contains("city_lat_bits") || !prefs.contains("city_lon_bits")) return false;
        double oldLat = Double.longBitsToDouble(prefs.getLong("city_lat_bits", 0));
        double oldLon = Double.longBitsToDouble(prefs.getLong("city_lon_bits", 0));
        float[] distance = new float[1];
        Location.distanceBetween(latitude, longitude, oldLat, oldLon, distance);
        return distance[0] <= 5000f;
    }

    /** Offline fallbacks for nearby Saudi cities if this device has no geocoding backend. */
    private static final Object[][] KNOWN_CITIES = {
            {"سكاكا", 29.9697, 40.2065}, {"دومة الجندل", 29.8110, 39.8660},
            {"القريات", 31.3330, 37.3430}, {"طبرجل", 30.5000, 38.2150},
            {"عرعر", 30.9750, 41.0380}, {"رفحاء", 29.6350, 43.5170},
            {"تبوك", 28.3838, 36.5662}, {"حائل", 27.5219, 41.6907},
            {"تيماء", 27.6260, 38.5340}, {"العلا", 26.6175, 37.9160},
            {"المدينة المنورة", 24.4672, 39.6111}, {"مكة المكرمة", 21.3891, 39.8579},
            {"جدة", 21.5433, 39.1728}, {"الرياض", 24.7136, 46.6753},
            {"الدمام", 26.4207, 50.0888}, {"الخبر", 26.2172, 50.1971},
            {"الطائف", 21.4373, 40.5127}, {"بريدة", 26.3592, 43.9818},
            {"عنيزة", 26.0843, 43.9936}, {"الأحساء", 25.3830, 49.5860},
            {"جازان", 16.8894, 42.5706}, {"أبها", 18.2164, 42.5053},
            {"خميس مشيط", 18.3064, 42.7292}, {"نجران", 17.5656, 44.2289},
            {"الباحة", 20.0129, 41.4677}, {"ينبع", 24.0891, 38.0637}
    };

    public static String label(SharedPreferences prefs, double latitude, double longitude) {
        if (hasCachedName(prefs, latitude, longitude)) {
            return "📍 " + prefs.getString("saved_city_name", "");
        }
        float nearestMeters = Float.MAX_VALUE;
        String nearestCity = null;
        for (Object[] candidate : KNOWN_CITIES) {
            float[] distance = new float[1];
            Location.distanceBetween(latitude, longitude, (double) candidate[1], (double) candidate[2], distance);
            if (distance[0] < nearestMeters) {
                nearestMeters = distance[0];
                nearestCity = (String) candidate[0];
            }
        }
        if (nearestCity != null && nearestMeters <= 25000f)
            return "📍 " + (nearestMeters > 12000f ? "بالقرب من " : "") + nearestCity;
        return "📍 " + String.format(Locale.US, "%.3f°, %.3f°", latitude, longitude);
    }

    public static void resolveCity(Context context, double latitude, double longitude, Consumer<String> onResolved) {
        if (!Geocoder.isPresent()) return;
        try {
            Geocoder geocoder = new Geocoder(context.getApplicationContext(), new Locale("ar", "SA"));
            if (Build.VERSION.SDK_INT >= 33) {
                geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) {
                        completeCity(addresses, onResolved);
                    }
                });
            } else {
                new Thread(() -> {
                    try { completeCity(geocoder.getFromLocation(latitude, longitude, 1), onResolved); }
                    catch (Exception ignored) {}
                }, "BAS-city-name").start();
            }
        } catch (Exception ignored) {}
    }

    private static void completeCity(List<Address> addresses, Consumer<String> callback) {
        if (addresses == null || addresses.isEmpty()) return;
        Address a = addresses.get(0);
        String city = a.getLocality();
        if (TextUtils.isEmpty(city)) city = a.getSubAdminArea();
        if (TextUtils.isEmpty(city)) city = a.getAdminArea();
        if (!TextUtils.isEmpty(city)) {
            String resolved = city.trim();
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(resolved));
        }
    }

    public static void saveCity(SharedPreferences prefs, String name, double latitude, double longitude) {
        prefs.edit().putString("saved_city_name", name)
                .putLong("city_lat_bits", Double.doubleToRawLongBits(latitude))
                .putLong("city_lon_bits", Double.doubleToRawLongBits(longitude))
                .apply();
    }
}
