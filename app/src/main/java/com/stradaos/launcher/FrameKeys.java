package com.stradaos.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Elsadek Car: los botones del marco (HOME, NAVI, RADIO, PHONE…) y del volante, con cualquier app delante.
 * Ve cada tecla que el sistema le pasa a Android: la anota (la prueba de teclas y el registro de «سجل العربية»)
 * y, si el usuario le asignó una función, la hace y no la deja seguir. Se activa en Accesibilidad.
 */
public class FrameKeys extends AccessibilityService {
    static volatile boolean running;
    /** La prueba de teclas de Ajustes: recibe cada tecla que llega. */
    static volatile KeyCb onTest;
    interface KeyCb { void key(int code); }

    @Override protected void onServiceConnected() { running = true; android.util.Log.i("ElsadekKeys", "servicio conectado"); }
    @Override public boolean onUnbind(Intent intent) { running = false; return super.onUnbind(intent); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        try {
            SharedPreferences p = getSharedPreferences("launcher", 0);
            return handle(this, p, e, true);
        } catch (Throwable t) { return false; }
    }

    /** Común al servicio y a la pantalla de inicio. true = la tecla queda usada. */
    static boolean handle(android.content.Context c, SharedPreferences p, KeyEvent e, boolean fromService) {
        int code = e.getKeyCode();
        android.util.Log.i("ElsadekKeys", (fromService ? "servicio " : "inicio ") + code + " " + e.getAction());
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_UNKNOWN) return false;
        KeyCb t = onTest;
        if (t != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) { final int cd = code; t.key(cd); }
            return true;   // durante la prueba no hace nada más
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) seen(p, code);
        String a = p.getString("keymap_" + code, "");
        if (a.length() == 0) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Intent i = new Intent(c, Main.class).putExtra("key_action", a)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            if ("play".equals(a) || "next".equals(a) || "prev".equals(a) || "split".equals(a) || "nav".equals(a) || "music".equals(a) || "phone".equals(a))
                i.putExtra("key_bg", true);   // sin mostrar la pantalla de inicio de por medio (lo que se pueda)
            c.startActivity(i);
        }
        return true;
    }

    /** Cada tecla nueva queda en «سجل العربية»: así se sabe desde lejos qué botones llegan a Android. */
    static void seen(SharedPreferences p, int code) {
        String s = p.getString("keys_seen", ",");
        if (s.contains("," + code + ",")) return;
        p.edit().putString("keys_seen", s + code + ",").apply();
        Extras.diag(p, "زرار وصل للشاشة: " + name(code));
    }

    static String name(int code) {
        String n;
        switch (code) {
            case KeyEvent.KEYCODE_HOME: n = "HOME"; break;
            case KeyEvent.KEYCODE_CALL: n = "اتصال"; break;
            case KeyEvent.KEYCODE_ENDCALL: n = "إنهاء المكالمة"; break;
            case KeyEvent.KEYCODE_MEDIA_NEXT: n = "اللي بعده ⏭"; break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: n = "اللي قبله ⏮"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: n = "تشغيل/إيقاف ⏯"; break;
            case KeyEvent.KEYCODE_VOLUME_UP: n = "علّي الصوت"; break;
            case KeyEvent.KEYCODE_VOLUME_DOWN: n = "وطّي الصوت"; break;
            case KeyEvent.KEYCODE_VOLUME_MUTE: n = "كتم الصوت"; break;
            default: n = null;
        }
        String raw = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
        return n != null ? n + " (" + raw + ")" : raw + " · " + code;
    }
}
