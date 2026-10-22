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
        if (newest != null) callback.accept(newest);

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

    public static String label(SharedPreferences prefs, double latitude, double longitude) {
        if (hasCachedName(prefs, latitude, longitude)) {
            return "📍 " + prefs.getString("saved_city_name", "");
        }
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
