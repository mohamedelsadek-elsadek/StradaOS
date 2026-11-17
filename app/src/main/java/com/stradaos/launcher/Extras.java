package com.stradaos.launcher;

import android.content.SharedPreferences;
import android.location.Location;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Funciones de la edición Elsadek Car (apagadas en StradaOS salvo que el usuario las active):
 * horario de oración calculado sin internet y resumen del viaje.
 */
final class Extras {
    private Extras() { }

    // ───────────────────────── horario de oración ─────────────────────────

    /** Nombres en español (se traducen con I18n): Fajr, Amanecer, Dhuhr, Asr, Maghrib, Isha. */
    static final String[] PRAYER = {"Fajr", "Amanecer", "Dhuhr", "Asr", "Maghrib", "Isha"};
    /** El Cairo: se usa hasta que llegue la primera posición GPS. */
    static final double CAIRO_LAT = 30.0444, CAIRO_LON = 31.2357;

    /**
     * Minutos del día (hora local) de Fajr, amanecer, Dhuhr, Asr, Maghrib e Isha. Método de la Autoridad General
     * Egipcia de Topografía (Fajr 19,5°, Isha 17,5°), Asr shafi'i. Fórmulas de PrayTimes.org; error ~1–2 min.
     * Un valor es -1 si ese día no ocurre (latitudes extremas).
     */
    static int[] prayerTimes(double lat, double lon, Calendar day) {
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, d = day.get(Calendar.DAY_OF_MONTH);
        Calendar noon = (Calendar) day.clone();
        noon.set(Calendar.HOUR_OF_DAY, 12); noon.set(Calendar.MINUTE, 0); noon.set(Calendar.SECOND, 0);
        double tz = TimeZone.getDefault().getOffset(noon.getTimeInMillis()) / 3600000.0;
        if (m <= 2) { y -= 1; m += 12; }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        double jd = Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5 - lon / 360.0;

        double[] sp = sun(jd + 0.5);
        double dec = sp[0], eqt = sp[1];
        double dhuhr = 12 - eqt;   // hora solar del mediodía (se ajusta a la hora local al final)
        double[] t = new double[6];
        t[0] = dhuhr - angleTime(19.5, lat, dec);
        t[1] = dhuhr - angleTime(0.833, lat, dec);
        t[2] = dhuhr;
        t[3] = dhuhr + asrTime(1, lat, dec);
        t[4] = dhuhr + angleTime(0.833, lat, dec);
        t[5] = dhuhr + angleTime(17.5, lat, dec);
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            if (Double.isNaN(t[i])) { out[i] = -1; continue; }
            double h = t[i] + tz - lon / 15.0;
            if (i == 2) h += 1 / 60.0;   // Dhuhr: un minuto después del mediodía, como los calendarios egipcios
            int min = (int) Math.round(h * 60);
            out[i] = ((min % 1440) + 1440) % 1440;
        }
        return out;
    }

    /** Declinación del sol (grados) y ecuación del tiempo (horas) para una fecha juliana. */
    private static double[] sun(double jd) {
        double D = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * D);
        double q = fixAngle(280.459 + 0.98564736 * D);
        double L = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * D;
        double ra = Math.toDegrees(Math.atan2(dcos(e) * dsin(L), dcos(L))) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        if (eqt > 12) eqt -= 24;
        if (eqt < -12) eqt += 24;
        double dec = Math.toDegrees(Math.asin(dsin(e) * dsin(L)));
        return new double[]{dec, eqt};
    }

    /** Horas entre el mediodía y el momento en que el sol está `angle` grados bajo el horizonte. */
    private static double angleTime(double angle, double lat, double dec) {
        double v = (-dsin(angle) - dsin(dec) * dsin(lat)) / (dcos(dec) * dcos(lat));
        if (v < -1 || v > 1) return Double.NaN;
        return Math.toDegrees(Math.acos(v)) / 15.0;
    }

    private static double asrTime(double factor, double lat, double dec) {
        double angle = -Math.toDegrees(Math.atan(1 / (factor + Math.tan(Math.toRadians(Math.abs(lat - dec))))));
        return angleTime(angle, lat, dec);
    }

    private static double dsin(double x) { return Math.sin(Math.toRadians(x)); }
    private static double dcos(double x) { return Math.cos(Math.toRadians(x)); }
    private static double fixAngle(double a) { a = a % 360; return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a % 24; return a < 0 ? a + 24 : a; }

    /** Próxima oración (sin contar el amanecer): {índice en PRAYER, minutos que faltan, minuto del día}. */
    static int[] nextPrayer(double lat, double lon, Calendar now) {
        int cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int[] t = prayerTimes(lat, lon, now);
        for (int i = 0; i < 6; i++) {
            if (i == 1 || t[i] < 0) continue;
            if (t[i] > cur) return new int[]{i, t[i] - cur, t[i]};
        }
        Calendar tomorrow = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        int[] t2 = prayerTimes(lat, lon, tomorrow);
        return new int[]{0, t2[0] + 1440 - cur, t2[0]};
    }

    // ───────────────────────── resumen del viaje ─────────────────────────
    // Un viaje empieza al moverse y termina tras 20 min quieto (o con la pantalla apagada ese tiempo).
    // Se guarda en prefs: trip_m (metros), trip_s (segundos en movimiento), trip_t (última vez en movimiento)
    // y el anterior en last_trip_m / last_trip_s / last_trip_end.

    static final long TRIP_GAP = 20 * 60 * 1000L;
    private static Location tripPrev;
    private static long tripSaved;
    private static float pendM;
    private static long pendS;

    static void tripStep(SharedPreferences p, Location l) {
        if (l == null || (l.hasAccuracy() && l.getAccuracy() > 40)) return;
        long now = System.currentTimeMillis();
        tripRollover(p, now);
        if (tripPrev == null) { tripPrev = l; return; }
        float d = tripPrev.distanceTo(l);
        float dt = Math.max(1, (l.getTime() - tripPrev.getTime()) / 1000f);
        boolean moving = (l.hasSpeed() && l.getSpeed() > 1.5f) || d >= 20;
        if (d < 20 && !moving) return;                 // ruido del GPS con el carro quieto
        if (d / dt > 70) { tripPrev = l; return; }      // salto imposible (> 250 km/h)
        if (d >= 20) { pendM += d; tripPrev = l; }
        if (moving) {
            pendS += (long) Math.min(dt, 10);
            if (d < 20) tripPrev = l;
        }
        if (now - tripSaved > 15000 || pendM > 300) tripFlush(p, now);
    }

    private static void tripFlush(SharedPreferences p, long now) {
        if (pendM == 0 && pendS == 0) return;
        SharedPreferences.Editor e0 = p.edit();
        if (p.getLong("trip_start", 0) == 0) e0.putLong("trip_start", now - pendS * 1000);
        e0.apply();
        p.edit().putFloat("trip_m", p.getFloat("trip_m", 0) + pendM)
            .putLong("trip_s", p.getLong("trip_s", 0) + pendS)
            .putLong("trip_t", now).apply();
        pendM = 0; pendS = 0; tripSaved = now;
    }

    /** Si pasaron 20 min sin moverse, el viaje actual pasa a ser el último. */
    static void tripRollover(SharedPreferences p, long now) {
        long last = p.getLong("trip_t", 0);
        if (last == 0 || now - last < TRIP_GAP || pendM > 0) return;
        float m = p.getFloat("trip_m", 0);
        long secs = p.getLong("trip_s", 0), start = p.getLong("trip_start", 0);
        SharedPreferences.Editor e = p.edit().putFloat("trip_m", 0).putLong("trip_s", 0).putLong("trip_t", 0).putLong("trip_start", 0);
        if (m >= 300) {
            e.putFloat("last_trip_m", m).putLong("last_trip_s", secs).putLong("last_trip_end", last);
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("id", newId()).put("t", "trip").put("date", day(start > 0 ? start : last))
                    .put("start", start > 0 ? start : last - secs * 1000).put("end", last)
                    .put("km", Math.round(m / 10f) / 100.0).put("min", Math.round(secs / 60f))
                    .put("odo", Math.round(p.getFloat("odo_base_km", 0) + p.getFloat("odo_gps_m", 0) / 1000f))
                    .put("by", "car").put("u", now);
                e.putString("trip_log", append(p.getString("trip_log", "[]"), d, 1000));
                e.putString("sync_q", append(p.getString("sync_q", "[]"), d, 2000));
            } catch (Throwable ignored) { }
        }
        e.apply();
        tripPrev = null;
    }

    static String newId() {
        return "x" + Long.toString(System.currentTimeMillis(), 36) + Long.toString((long) (Math.random() * 2176782336L), 36);
    }

    static String day(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(ms));
    }

    private static String append(String json, org.json.JSONObject d, int max) throws org.json.JSONException {
        org.json.JSONArray a = new org.json.JSONArray(json), out = new org.json.JSONArray();
        int from = Math.max(0, a.length() + 1 - max);
        for (int i = from; i < a.length(); i++) out.put(a.get(i));
        out.put(d);
        return out.toString();
    }

    // ───────────────────────── sincronización (Firebase de los programas de Mohamed) ─────────────────────────
    // Cada viaje terminado va a <db>/car_log/docs/<id> (como los demás programas: {id,t,u,...}) y el estado del carro
    // (kilometraje, viaje en curso) a <db>/car_log/state. Sin ubicación: la base no tiene contraseña.

    private static volatile boolean syncing;

    static void sync(final android.content.Context c, final SharedPreferences p, final String db) {
        if (db == null || db.length() == 0 || syncing) return;
        long now = System.currentTimeMillis();
        boolean queued = !"[]".equals(p.getString("sync_q", "[]"));
        if (!queued && now - p.getLong("sync_state_at", 0) < 10 * 60 * 1000L) return;
        syncing = true;
        // la versión de prueba escribe aparte, para no mezclar viajes de prueba con los del carro
        final String root = db + (c.getPackageName().endsWith(".debug") ? "/car_log_test" : "/car_log");
        new Thread(new Runnable() { public void run() {
            try {
                org.json.JSONArray q = new org.json.JSONArray(p.getString("sync_q", "[]"));
                int sent = 0;
                for (int i = 0; i < q.length(); i++) {
                    org.json.JSONObject d = q.getJSONObject(i);
                    put(root + "/docs/" + d.getString("id") + ".json", d.toString());
                    sent++;
                }
                if (sent > 0) {   // se quitan solo los enviados (pudo llegar otro viaje mientras tanto)
                    org.json.JSONArray cur = new org.json.JSONArray(p.getString("sync_q", "[]")), rest = new org.json.JSONArray();
                    for (int i = sent; i < cur.length(); i++) rest.put(cur.get(i));
                    p.edit().putString("sync_q", rest.toString()).apply();
                }
                org.json.JSONObject st = new org.json.JSONObject();
                long t = System.currentTimeMillis();
                st.put("odo", Math.round(p.getFloat("odo_base_km", 0) + p.getFloat("odo_gps_m", 0) / 1000f))
                    .put("trip_km", Math.round(tripKm(p) * 100) / 100.0).put("trip_min", tripMin(p))
                    .put("car", p.getString("car_name", "")).put("ver", Updates.currentVersion(c)).put("u", t);
                put(root + "/state.json", st.toString());
                p.edit().putLong("sync_state_at", t).putLong("sync_ok_at", t).apply();
            } catch (Throwable e) {
                Guard.log("sync: " + e);
            } finally {
                syncing = false;
            }
        }}).start();
    }

    private static void put(String url, String body) throws Exception {
        java.net.HttpURLConnection h = Net.open(url, 15000);
        try {
            h.setRequestMethod("PUT");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            java.io.OutputStream o = h.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = h.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        } finally {
            h.disconnect();
        }
    }

    static float tripKm(SharedPreferences p) { return (p.getFloat("trip_m", 0) + pendM) / 1000f; }
    static long tripMin(SharedPreferences p) { return (p.getLong("trip_s", 0) + pendS) / 60; }
}
