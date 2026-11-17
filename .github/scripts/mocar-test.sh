#!/bin/bash
# Instala MoCar en el emulador, lo abre y guarda capturas + si se cerró.
set -x
mkdir -p out
adb wait-for-device
adb shell settings put global window_animation_scale 0
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 23 ]; then G=-g; else G=; fi
adb install -r $G mocar/MoCar-Elsadek.apk > out/install.txt 2>&1; cat out/install.txt
grep -q Success out/install.txt || { echo "INSTALL FAILED"; exit 1; }
adb logcat -c
adb shell am start -n com.mocar.launcher/.Main
sleep 20
adb exec-out screencap -p > out/1-abierto.png
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n com.mocar.launcher/.Main; sleep 6
adb exec-out screencap -p > out/2-reabierto.png
adb shell input keyevent KEYCODE_HOME; sleep 4
adb exec-out screencap -p > out/3-inicio.png
{ adb shell ps -A 2>/dev/null; adb shell ps; } | grep com.mocar.launcher | sort -u > out/pid.txt
adb logcat -d -b crash > out/crash.txt 2>/dev/null || true
adb logcat -d | grep -E "FATAL|AndroidRuntime|mocar" | tail -200 > out/logcat.txt
echo "pid: $(cat out/pid.txt)"; echo "crash lines: $(grep -c FATAL out/logcat.txt)"
grep -q com.mocar.launcher out/pid.txt || { echo "APP NOT RUNNING"; exit 1; }
! grep -q "FATAL" out/logcat.txt || { echo "CRASH"; exit 1; }
