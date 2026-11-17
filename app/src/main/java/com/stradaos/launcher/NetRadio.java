package com.stradaos.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Radio por internet (Elsadek Car): reproduce emisoras en vivo con MediaPlayer en un servicio en primer plano.
 * Publica una MediaSession (Android 5+), así el reproductor del inicio muestra la emisora y sus botones la controlan
 * (⏮⏭ = emisora anterior/siguiente de las favoritas). Las emisoras vienen del directorio abierto radio-browser.info.
 */
public class NetRadio extends Service {

    static final class Station {
        String name, url, tags, favicon;
        int bitrate;
        JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("n", name).put("u", url).put("t", tags == null ? "" : tags).put("b", bitrate).put("f", favicon == null ? "" : favicon); } catch (Throwable ignored) { }
            return o;
        }
        static Station of(JSONObject o) {
            Station s = new Station();
            s.name = o.optString("n"); s.url = o.optString("u"); s.tags = o.optString("t"); s.bitrate = o.optInt("b"); s.favicon = o.optString("f");
            return s;
        }
    }

    // estado que el inicio puede leer
    static volatile Station current;
    static volatile int state;            // 0 parado, 1 conectando, 2 sonando, 3 error
    static volatile String lastError = "";
    static Runnable onChange;              // lo pone Main para refrescar su ventana

    private static final String CH = "netradio";
    private MediaPlayer mp;
    private Object session;                // android.media.session.MediaSession (API 21+)
    private final Handler h = new Handler(Looper.getMainLooper());
    private int retries;
    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int f) {
            if (f == AudioManager.AUDIOFOCUS_LOSS) stopPlayback();
            else if (mp != null) try { mp.setVolume(f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.25f : 1f, f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.25f : 1f); } catch (Throwable ignored) { }
        }
    };

    // ───────────────────────── control desde afuera ─────────────────────────

    static void play(Context c, Station s) {
        current = s;
        c.getSharedPreferences("launcher", 0).edit().putString("radio_last", s.json().toString()).apply();
        Intent i = new Intent(c, NetRadio.class).setAction("play");
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        try { c.startService(new Intent(c, NetRadio.class).setAction("stop")); } catch (Throwable ignored) { }
    }

    /** ▶ del inicio sin emisora sonando: la última que se escuchó. */
    static boolean resume(Context c) {
        try {
            String j = c.getSharedPreferences("launcher", 0).getString("radio_last", null);
            if (j == null) return false;
            play(c, Station.of(new JSONObject(j)));
            return true;
        } catch (Throwable e) { return false; }
    }

    static void step(Context c, int dir) {
        List<Station> f = favorites(c.getSharedPreferences("launcher", 0));
        if (f.isEmpty() || current == null) return;
        int i = 0;
        for (int k = 0; k < f.size(); k++) if (f.get(k).url.equals(current.url)) i = k;
        play(c, f.get(((i + dir) % f.size() + f.size()) % f.size()));
    }

    static List<Station> favorites(SharedPreferences p) {
        List<Station> out = new ArrayList<Station>();
        try {
            JSONArray a = new JSONArray(p.getString("radio_favs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Station.of(a.getJSONObject(i)));
        } catch (Throwable ignored) { }
        return out;
    }

    static boolean isFav(SharedPreferences p, Station s) {
        for (Station f : favorites(p)) if (f.url.equals(s.url)) return true;
        return false;
    }

    static void toggleFav(SharedPreferences p, Station s) {
        List<Station> f = favorites(p);
        JSONArray a = new JSONArray();
        boolean had = false;
        for (Station x : f) { if (x.url.equals(s.url)) { had = true; continue; } a.put(x.json()); }
        if (!had) a.put(s.json());
        p.edit().putString("radio_favs", a.toString()).apply();
    }

    // ───────────────────────── directorio radio-browser.info ─────────────────────────

    private static final String[] API = {"https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info",
        "https://at1.api.radio-browser.info", "https://all.api.radio-browser.info"};

    /** Emisoras de Egipto (las más escuchadas) o, con texto, la búsqueda por nombre en todo el mundo. En hilo aparte. */
    static List<Station> fetch(String query) throws Exception {
        String path = query == null || query.trim().length() == 0
            ? "/json/stations/bycountrycodeexact/EG?hidebroken=true&order=clickcount&reverse=true&limit=120"
            : "/json/stations/search?name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8") + "&hidebroken=true&order=clickcount&reverse=true&limit=80";
        Exception last = null;
        for (String host : API) {
            try {
                JSONArray a = new JSONArray(new String(Net.get(host + path, 15000), "UTF-8"));
                List<Station> out = new ArrayList<Station>();
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Station s = new Station();
                    s.name = o.optString("name", "").trim();
                    s.url = o.optString("url_resolved", o.optString("url", "")).trim();
                    s.tags = o.optString("tags", "");
                    s.bitrate = o.optInt("bitrate", 0);
                    s.favicon = o.optString("favicon", "");
                    if (s.name.length() == 0 || !s.url.startsWith("http") || !seen.add(s.name.toLowerCase())) continue;
                    out.add(s);
                }
                return out;
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new Exception("sin respuesta");
    }

    // ───────────────────────── servicio ─────────────────────────

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String a = i != null ? i.getAction() : null;
        if ("stop".equals(a)) { stopPlayback(); stopSelf(); return START_NOT_STICKY; }
        if ("next".equals(a)) { step(this, 1); return START_NOT_STICKY; }
        if ("prev".equals(a)) { step(this, -1); return START_NOT_STICKY; }
        foreground();
        if ("play".equals(a) && current != null) { retries = 0; start(current); }
        return START_NOT_STICKY;
    }

    private void start(final Station s) {
        release();
        state = 1; lastError = "";
        changed();
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(s.url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) { if (m != mp) return; m.start(); state = 2; retries = 0; changed(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    lastError = what + "/" + extra;
                    Guard.log("radio: error " + lastError + " en " + s.url);
                    // se corta (túnel, cambio de antena): reintenta 3 veces
                    if (retries++ < 3 && current == s) h.postDelayed(new Runnable() { public void run() { if (current == s) start(s); } }, 2500);
                    else { state = 3; changed(); }
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { if (retries++ < 3 && current == s) start(s); }
            });
            mp.prepareAsync();
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            state = 3; changed();
        }
    }

    private void stopPlayback() {
        current = null;
        release();
        state = 0;
        try { ((AudioManager) getSystemService(AUDIO_SERVICE)).abandonAudioFocus(focus); } catch (Throwable ignored) { }
        changed();
        if (Build.VERSION.SDK_INT >= 21 && session != null) {
            try { ((android.media.session.MediaSession) session).setActive(false); ((android.media.session.MediaSession) session).release(); } catch (Throwable ignored) { }
            session = null;
        }
        stopForeground(true);
    }

    private void release() {
        if (mp == null) return;
        try { mp.reset(); mp.release(); } catch (Throwable ignored) { }
        mp = null;
    }

    @Override public void onDestroy() { stopPlayback(); super.onDestroy(); }

    /** Notificación (obligatoria para sonar en segundo plano) y MediaSession. */
    private void changed() {
        h.post(new Runnable() { public void run() {
            try { if (onChange != null) onChange.run(); } catch (Throwable ignored) { }
            if (current != null) { foreground(); updateSession(); }
        }});
    }

    @SuppressWarnings("deprecation")
    private void foreground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, I18n.t("Radio por internet"), NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NetRadio.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(current != null ? current.name : I18n.t("Radio por internet"))
                .setContentText(I18n.t(state == 1 ? "Conectando…" : state == 3 ? "No se pudo reproducir" : "Radio por internet"))
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, I18n.t("Detener"), stop);
            startForeground(77, b.build());
        } catch (Throwable e) { Guard.log("radio: notificación " + e); }
    }

    private void updateSession() {
        if (Build.VERSION.SDK_INT < 21 || current == null) return;
        try {
            android.media.session.MediaSession ms = (android.media.session.MediaSession) session;
            if (ms == null) {
                ms = new android.media.session.MediaSession(this, "ElsadekRadio");
                ms.setCallback(new android.media.session.MediaSession.Callback() {
                    @Override public void onPlay() { if (current != null) start(current); else resume(NetRadio.this); }
                    @Override public void onPause() { Station keep = current; stopPlayback(); current = keep; }
                    @Override public void onStop() { stopPlayback(); stopSelf(); }
                    @Override public void onSkipToNext() { step(NetRadio.this, 1); }
                    @Override public void onSkipToPrevious() { step(NetRadio.this, -1); }
                });
                ms.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                session = ms;
            }
            ms.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, current.name)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, I18n.t("Radio por internet"))
                .build());
            int st = state == 2 ? android.media.session.PlaybackState.STATE_PLAYING
                : state == 1 ? android.media.session.PlaybackState.STATE_BUFFERING : android.media.session.PlaybackState.STATE_PAUSED;
            ms.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE
                    | android.media.session.PlaybackState.ACTION_PLAY_PAUSE | android.media.session.PlaybackState.ACTION_STOP
                    | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build());
            ms.setActive(true);
        } catch (Throwable e) { Guard.log("radio: sesión " + e); }
    }
}
