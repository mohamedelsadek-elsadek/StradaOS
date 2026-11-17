#!/bin/bash
# Elsadek Car (versión de prueba): instala, abre y saca capturas del inicio, el viaje y el horario de oración.
set -x
mkdir -p capturas
P=com.elsadek.car.debug
A=$P/com.stradaos.launcher.Main
adb wait-for-device
adb install -r -g app/build/outputs/apk/debug/*.apk
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION || true
adb logcat -c
shot() { sleep "$2"; adb exec-out screencap -p > "capturas/$1.png"; }
adb shell am start -n $A --es prueba saltar; shot 1-inicio 15
adb shell am start -n $A --es prueba viaje-demo; shot 2-viaje 5
adb shell am start -n $A --es prueba oracion; shot 3-oracion 5
adb shell input keyevent KEYCODE_BACK
adb shell am start -n $A --es prueba ajustes:1; shot 4-ajustes 6
adb shell am start -n $A --es prueba inicio; sleep 3
adb shell wm size 720x1280; adb shell wm density 240
adb shell am start -n $A --es prueba inicio; shot 5-vertical 10
adb shell wm size reset; adb shell wm density reset
adb logcat -d | grep -E "FATAL|AndroidRuntime" | tail -60 > capturas/errores.txt
adb shell dumpsys package $P | grep -E "versionName|firstInstall" > capturas/paquete.txt
cat capturas/errores.txt
