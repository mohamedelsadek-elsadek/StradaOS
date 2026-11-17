package com.stradaos.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Elsadek Car: ¿el carro va por una autopista/vía rápida (motorway/trunk de OpenStreetMap) o por la ciudad?
 * Se piden a Overpass solo las vías rápidas en 1,5 km a la redonda (poco tráfico de datos) y se vuelven a pedir al
 * alejarse 1 km. Sin datos (sin internet) se responde «desconocido» y el aviso usa el límite de carretera.
 */
final class Roads {
    private Roads() { }

    private static final class Way { double[] la, lo; int max; }

    private static volatile List<Way> ways = Collections.emptyList();
    private static volatile double cLat = Double.NaN, cLon = Double.NaN;
    private static volatile long fetchedAt, failedAt;
    private static volatile boolean loading;

    /** -1 = desconocido; 0 = ciudad; >0 = vía rápida (el valor es su maxspeed, o 1 si no tiene). */
    static int at(double lat, double lon) {
        refresh(lat, lon);
        if (Double.isNaN(cLat) || dist(lat, lon, cLat, cLon) > 1400) return -1;
        int best = 0;
        double bd = 30;   // metros: a menos de esto de una vía rápida, se va por ella
        for (Way w : ways) {
            for (int i = 1; i < w.la.length; i++) {
                double d = segDist(lat, lon, w.la[i - 1], w.lo[i - 1], w.la[i], w.lo[i]);
                if (d < bd) { bd = d; best = w.max > 0 ? w.max : 1; }
            }
        }
        return best;
    }

    private static void refresh(final double lat, final double lon) {
        long now = System.currentTimeMillis();
        if (loading || now - failedAt < 60000) return;
        if (!Double.isNaN(cLat) && dist(lat, lon, cLat, cLon) < 900 && now - fetchedAt < 6 * 3600000L) return;
        loading = true;
        new Thread(new Runnable() { public void run() {
            try {
                String q = "[out:json][timeout:20];way(around:1500," + lat + "," + lon + ")"
                    + "[highway~\"^(motorway|trunk|motorway_link|trunk_link)$\"];out tags geom;";
                byte[] b = Net.get("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(q, "UTF-8"), 25000);
                JSONArray el = new JSONObject(new String(b, "UTF-8")).optJSONArray("elements");
                List<Way> out = new ArrayList<Way>();
                for (int i = 0; el != null && i < el.length(); i++) {
                    JSONObject o = el.getJSONObject(i);
                    JSONArray g = o.optJSONArray("geometry");
                    if (g == null || g.length() < 2) continue;
                    Way w = new Way();
                    w.la = new double[g.length()]; w.lo = new double[g.length()];
                    for (int k = 0; k < g.length(); k++) { w.la[k] = g.getJSONObject(k).getDouble("lat"); w.lo[k] = g.getJSONObject(k).getDouble("lon"); }
                    JSONObject t = o.optJSONObject("tags");
                    String ms = t != null ? t.optString("maxspeed", "") : "";
                    try { w.max = Integer.parseInt(ms.replaceAll("[^0-9]", "")); } catch (Throwable e) { w.max = 0; }
                    if (w.max < 30 || w.max > 160) w.max = 0;
                    out.add(w);
                }
                ways = out; cLat = lat; cLon = lon; fetchedAt = System.currentTimeMillis();
            } catch (Throwable e) {
                failedAt = System.currentTimeMillis();
            } finally {
                loading = false;
            }
        }}).start();
    }

    static int count() { return ways.size(); }

    static double dist(double a1, double o1, double a2, double o2) {
        double x = Math.toRadians(o2 - o1) * Math.cos(Math.toRadians((a1 + a2) / 2)), y = Math.toRadians(a2 - a1);
        return Math.sqrt(x * x + y * y) * 6371000;
    }

    /** Distancia (m) del punto al segmento, en un plano local. */
    private static double segDist(double la, double lo, double la1, double lo1, double la2, double lo2) {
        double k = Math.cos(Math.toRadians(la)) * 111320, m = 110540;
        double px = (lo - lo1) * k, py = (la - la1) * m, vx = (lo2 - lo1) * k, vy = (la2 - la1) * m;
        double len = vx * vx + vy * vy, t = len > 0 ? Math.max(0, Math.min(1, (px * vx + py * vy) / len)) : 0;
        double dx = px - t * vx, dy = py - t * vy;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
