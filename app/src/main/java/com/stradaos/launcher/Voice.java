package com.stradaos.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Elsadek Car: voz en árabe (TTS del sistema) y grabaciones de assets/voice/*.mp3, una tras otra.
 * Baja la música mientras habla (foco de audio «transitorio con atenuación»).
 */
final class Voice {
    private Voice() { }

    private static TextToSpeech tts;
    private static boolean ready, arOk;
    private static final List<String> queue = new ArrayList<String>();   // «t:texto» o «a:archivo.mp3»
    private static boolean busy;
    private static MediaPlayer mp;
    private static Context app;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int f) { }
    };

    static void init(Context c) {
        app = c.getApplicationContext();
        if (tts != null) return;
        try {
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS || tts == null) return;
                    int r = tts.setLanguage(new Locale("ar", "EG"));
                    if (r < 0) r = tts.setLanguage(new Locale("ar"));
                    arOk = r >= 0;
                    tts.setSpeechRate(0.95f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        public void onStart(String id) { }
                        public void onDone(String id) { h.post(new Runnable() { public void run() { next(); } }); }
                        public void onError(String id) { h.post(new Runnable() { public void run() { next(); } }); }
                    });
                    ready = true;
                    if (!busy && !queue.isEmpty()) next();
                }
            });
        } catch (Throwable e) { Guard.log("voz: " + e); }
    }

    /** ¿Hay voz árabe instalada? (si no, el sistema leería el árabe con otra voz o no lo leería) */
    static boolean arabicOk() { return arOk; }

    static void say(String text) { add("t:" + text); }
    static void play(String asset) { add("a:" + asset); }
    static boolean busy() { return busy || !queue.isEmpty(); }

    static void stop() {
        queue.clear();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        try { if (mp != null) { mp.release(); mp = null; } } catch (Throwable ignored) { }
        finish();
    }

    /** ¿Existe la grabación? */
    static boolean hasAsset(Context c, String name) {
        try { c.getAssets().openFd(name).close(); return true; } catch (Throwable e) { return false; }
    }

    private static void add(String item) {
        queue.add(item);
        if (!busy && (ready || item.startsWith("a:"))) next();
    }

    private static void next() {
        if (queue.isEmpty()) { finish(); return; }
        String it = queue.get(0);
        if (it.startsWith("t:") && !ready) { finish(); return; }   // espera a que arranque el TTS (onInit sigue)
        queue.remove(0);
        if (!busy) {
            busy = true;
            try {
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            } catch (Throwable ignored) { }
        }
        try {
            if (it.startsWith("a:")) playAsset(it.substring(2));
            else speak(it.substring(2));
        } catch (Throwable e) {
            Guard.log("voz: " + e);
            h.post(new Runnable() { public void run() { next(); } });
        }
    }

    @SuppressWarnings("deprecation")
    private static void speak(String text) {
        if (Build.VERSION.SDK_INT >= 21) {
            android.os.Bundle b = new android.os.Bundle();
            b.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_ADD, b, "u" + System.nanoTime());
        } else {
            HashMap<String, String> p = new HashMap<String, String>();
            p.put(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
            p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "u" + System.nanoTime());
            tts.speak(text, TextToSpeech.QUEUE_ADD, p);
        }
    }

    private static void playAsset(String name) throws Exception {
        AssetFileDescriptor fd = app.getAssets().openFd(name);
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        fd.close();
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer m) { try { m.release(); } catch (Throwable ignored) { } mp = null; next(); }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int w, int e) { try { m.release(); } catch (Throwable ignored) { } mp = null; next(); return true; }
        });
        mp.prepare();
        mp.start();
    }

    private static void finish() {
        if (!busy) return;
        busy = false;
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.abandonAudioFocus(focus);
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── textos en árabe ─────────────────────────

    static final String DUA_RUKUB = "بسم الله، الحمد لله. سبحان الذي سخر لنا هذا وما كنا له مقرنين، وإنا إلى ربنا لمنقلبون.";
    static final String DUA_SAFAR = "الله أكبر، الله أكبر، الله أكبر. سبحان الذي سخر لنا هذا وما كنا له مقرنين، وإنا إلى ربنا لمنقلبون. "
        + "اللهم إنا نسألك في سفرنا هذا البر والتقوى، ومن العمل ما ترضى. اللهم هون علينا سفرنا هذا، واطو عنا بعده. "
        + "اللهم أنت الصاحب في السفر، والخليفة في الأهل. اللهم إني أعوذ بك من وعثاء السفر، وكآبة المنظر، وسوء المنقلب في المال والأهل.";

    static final String[] PRAYER_AR = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};

    /** «الساعة ٦ و١٩ دقيقة» (sin la parte del día) */
    static String clockWords(int h24, int m) {
        String w = timeWords(h24, m);
        return w.substring(0, w.lastIndexOf(' '));
    }

    /** «الساعة ٨ وربع الصبح» */
    static String timeWords(int h24, int m) {
        int h = h24 % 12 == 0 ? 12 : h24 % 12;
        String part = h24 < 5 ? "بالليل" : h24 < 12 ? "الصبح" : h24 < 15 ? "الضهر" : h24 < 18 ? "العصر" : h24 < 20 ? "المغرب" : "بالليل";
        String s;
        if (m == 0) s = h + " بالظبط";
        else if (m == 15) s = h + " وربع";
        else if (m == 30) s = h + " ونص";
        else if (m == 45) s = ((h % 12) + 1) + " إلا ربع";
        else s = h + " و " + m + " دقيقة";
        return "الساعة " + s + " " + part;
    }

    /** «ساعة و٢٧ دقيقة» */
    static String durWords(int min) {
        int h = min / 60, m = min % 60;
        String hs = h == 0 ? "" : h == 1 ? "ساعة" : h == 2 ? "ساعتين" : h + " ساعات";
        String ms = m == 0 ? "" : m == 1 ? "دقيقة" : m == 2 ? "دقيقتين" : m + (m <= 10 ? " دقايق" : " دقيقة");
        if (hs.length() == 0) return ms.length() == 0 ? "دقيقة" : ms;
        return ms.length() == 0 ? hs : hs + " و" + ms;
    }

    /** Clima en árabe hablado (códigos WMO de Open-Meteo). */
    static String sky(int code) {
        if (code == 0) return "صافي";
        if (code <= 2) return "صافي مع شوية سحاب";
        if (code == 3) return "مغيم";
        if (code == 45 || code == 48) return "فيه شبورة";
        if (code >= 51 && code <= 57) return "فيه رذاذ";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "ممطر";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "فيه تلج";
        if (code >= 95) return "فيه عواصف رعدية";
        return "معتدل";
    }
}
