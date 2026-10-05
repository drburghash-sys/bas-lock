package com.bas.lock;

import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Offline solar prayer-time calculator tuned for Umm al-Qura style settings:
 * Fajr 18.5°, Isha 90 min after Maghrib (120 min in Ramadan), standard Asr.
 * Designed for a lock-screen dashboard, not as a legal/religious authority.
 */
public final class PrayerTimesCalculator {
    public static final class Times {
        public final ZonedDateTime fajr, sunrise, dhuhr, asr, maghrib, isha;
        public Times(ZonedDateTime fajr, ZonedDateTime sunrise, ZonedDateTime dhuhr,
                     ZonedDateTime asr, ZonedDateTime maghrib, ZonedDateTime isha) {
            this.fajr = fajr; this.sunrise = sunrise; this.dhuhr = dhuhr;
            this.asr = asr; this.maghrib = maghrib; this.isha = isha;
        }
        public Map<String, ZonedDateTime> asMap() {
            LinkedHashMap<String, ZonedDateTime> m = new LinkedHashMap<>();
            m.put("الفجر", fajr); m.put("الشروق", sunrise); m.put("الظهر", dhuhr);
            m.put("العصر", asr); m.put("المغرب", maghrib); m.put("العشاء", isha);
            return m;
        }
    }

    private PrayerTimesCalculator() {}

    public static Times calculate(LocalDate date, double latitude, double longitude,
                                  ZoneId zone, boolean ramadan) {
        ZonedDateTime noonLocal = date.atTime(12, 0).atZone(zone);
        double tzMinutes = noonLocal.getOffset().getTotalSeconds() / 60.0;
        int day = date.getDayOfYear();
        double gamma = 2.0 * Math.PI / 365.0 * (day - 1);

        double eqTime = 229.18 * (0.000075
                + 0.001868 * Math.cos(gamma)
                - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma)
                - 0.040849 * Math.sin(2 * gamma));

        double decl = 0.006918
                - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma)
                + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma)
                + 0.00148 * Math.sin(3 * gamma);

        double solarNoonMin = 720.0 - 4.0 * longitude - eqTime + tzMinutes;
        double sunriseDelta = hourAngleMinutes(latitude, decl, 90.833);
        double fajrDelta = hourAngleMinutes(latitude, decl, 108.5);

        double asrElevation = Math.toDegrees(Math.atan(
                1.0 / (1.0 + Math.tan(Math.toRadians(Math.abs(latitude - Math.toDegrees(decl)))))
        ));
        double asrZenith = 90.0 - asrElevation;
        double asrDelta = hourAngleMinutes(latitude, decl, asrZenith);

        ZonedDateTime fajr = fromMinutes(date, solarNoonMin - fajrDelta, zone);
        ZonedDateTime sunrise = fromMinutes(date, solarNoonMin - sunriseDelta, zone);
        ZonedDateTime dhuhr = fromMinutes(date, solarNoonMin, zone);
        ZonedDateTime asr = fromMinutes(date, solarNoonMin + asrDelta, zone);
        ZonedDateTime maghrib = fromMinutes(date, solarNoonMin + sunriseDelta, zone);
        ZonedDateTime isha = maghrib.plusMinutes(ramadan ? 120 : 90);

        return new Times(fajr, sunrise, dhuhr, asr, maghrib, isha);
    }

    private static double hourAngleMinutes(double latitudeDeg, double declRad, double zenithDeg) {
        double lat = Math.toRadians(latitudeDeg);
        double zen = Math.toRadians(zenithDeg);
        double cosH = (Math.cos(zen) - Math.sin(lat) * Math.sin(declRad)) /
                (Math.cos(lat) * Math.cos(declRad));
        cosH = Math.max(-1.0, Math.min(1.0, cosH));
        return Math.toDegrees(Math.acos(cosH)) * 4.0;
    }

    private static ZonedDateTime fromMinutes(LocalDate date, double minutes, ZoneId zone) {
        long seconds = Math.round(minutes * 60.0);
        return date.atStartOfDay(zone).plusSeconds(seconds);
    }
}
