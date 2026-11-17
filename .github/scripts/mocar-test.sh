#!/bin/bash
# Instala MoCar en el emulador, lo abre y guarda capturas + si se cerró.
set -x
mkdir -p out
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb install -r -g mocar/MoCar-Elsadek.apk 2>&1 | tee out/install.txt || adb install -r mocar/MoCar-Elsadek.apk 2>&1 | tee out/install.txt
adb logcat -c
adb shell am start -n com.mocar.launcher/.Main
sleep 20
adb exec-out screencap -p > out/1-abierto.png
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n com.mocar.launcher/.Main; sleep 6
adb exec-out screencap -p > out/2-reabierto.png
adb shell input keyevent KEYCODE_HOME; sleep 4
adb exec-out screencap -p > out/3-inicio.png
adb shell pidof com.mocar.launcher > out/pid.txt || adb shell ps | grep mocar > out/pid.txt
adb logcat -d -b crash > out/crash.txt 2>/dev/null || true
adb logcat -d | grep -E "FATAL|AndroidRuntime|mocar" | tail -200 > out/logcat.txt
echo "pid: $(cat out/pid.txt)"; echo "crash lines: $(grep -c FATAL out/logcat.txt)"
