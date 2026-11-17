#!/bin/bash
# Elsadek Car (versión de prueba): instala, abre y saca capturas del inicio, el viaje y el horario de oración.
set -x
mkdir -p capturas
P=com.elsadek.car.debug
A=$P/com.stradaos.launcher.Main
adb wait-for-device
# como en Egipto: hora de El Cairo y ubicación en El Cairo
adb root; sleep 3; adb wait-for-device
adb shell settings put global auto_time_zone 0
adb shell setprop persist.sys.timezone Africa/Cairo
adb shell cmd location set-location-enabled true || true
adb emu geo fix 31.2357 30.0444
adb install -r -g app/build/outputs/apk/debug/*.apk
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION || true
adb logcat -c
shot() { sleep "$2"; adb exec-out screencap -p > "capturas/$1.png"; }
adb shell am start -n $A --es prueba saltar; sleep 8
adb emu geo fix 31.2358 30.0445; shot 1-inicio 8
adb shell am start -n $A --es prueba viaje-demo; shot 2-viaje 5
adb shell am start -n $A --es prueba oracion; shot 3-oracion 5
adb shell input keyevent KEYCODE_BACK
adb shell am start -n $A --es prueba ajustes:1; shot 4-ajustes 6
# viaje terminado → se sincroniza con Firebase (car_log_test en la versión de prueba)
adb shell am start -n $A --es prueba viaje-cerrar; sleep 20
DB=https://my-app-sync-11ad9-default-rtdb.europe-west1.firebasedatabase.app
curl -s "$DB/car_log_test.json" > capturas/firebase.json; cat capturas/firebase.json; echo
curl -s -X DELETE "$DB/car_log_test.json" > /dev/null   # se borra la prueba
adb shell am start -n $A --es prueba inicio; sleep 3
adb shell wm size 720x1280; adb shell wm density 240
adb shell am start -n $A --es prueba inicio; shot 5-vertical 10
adb shell wm size reset; adb shell wm density reset
adb logcat -d | grep -E "FATAL|AndroidRuntime" | tail -60 > capturas/errores.txt
adb shell dumpsys package $P | grep -E "versionName|firstInstall" > capturas/paquete.txt
cat capturas/errores.txt
