#!/bin/bash
# Elsadek Car (versión de prueba): instala, abre y saca capturas del inicio, el viaje y el horario de oración.
set -x
mkdir -p capturas
P=com.elsadek.car.debug
A=$P/com.stradaos.launcher.Main
adb wait-for-device
# como en Egipto: hora de El Cairo y ubicación en El Cairo
adb root; sleep 3; adb wait-for-device
adb shell sysctl -w net.ipv6.conf.all.disable_ipv6=1 >/dev/null 2>&1   # la máquina de GitHub no tiene salida IPv6
adb shell sysctl -w net.ipv6.conf.default.disable_ipv6=1 >/dev/null 2>&1
adb shell settings put global auto_time_zone 0
adb shell setprop persist.sys.timezone Africa/Cairo
adb shell cmd location set-location-enabled true || true
adb emu geo fix 31.2357 30.0444
adb install -r -g app/build/outputs/apk/debug/*.apk
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION || true
adb logcat -c
shot() { sleep "$2"; adb exec-out screencap -p > "capturas/$1.png"; }
adb shell am start -n $A --es prueba saltar; sleep 8
adb shell am start -n $A --es prueba odo-demo; sleep 2
adb emu geo fix 31.2358 30.0445; shot 1-inicio 8
adb shell am start -n $A --es prueba viaje-demo; shot 2-viaje 5
adb shell am start -n $A --es prueba oracion; shot 3-oracion 5
adb shell input keyevent KEYCODE_BACK
adb shell am start -n $A --es prueba ajustes:1; shot 4-ajustes 6
# asistente de voz: saludo y aviso de velocidad (el texto queda en el registro como ElsadekVoz)
adb shell am start -n $A --es prueba saludo; sleep 8
adb shell am start -n $A --es prueba velocidad; sleep 4
adb shell am start -n $A --es prueba mant-demo; sleep 3
adb shell am start -n $A --es prueba via; sleep 25
Q='[out:json][timeout:20];way(around:1500,29.9890,31.2110)[highway~"^(motorway|trunk|motorway_link|trunk_link)$"];out tags geom;'
for U in https://overpass-api.de/api/interpreter https://overpass.private.coffee/api/interpreter https://maps.mail.ru/osm/tools/overpass/api/interpreter; do
  echo "$U: $(curl -s -m 40 -A 'ElsadekCar/1.0 (Android car launcher)' -o /dev/null -w '%{http_code} %{size_download}' -G --data-urlencode "data=$Q" $U)" >> capturas/overpass.txt
done
adb shell am start -n $A --es prueba ajustes:3; sleep 3
adb exec-out screencap -p > capturas/6-ajustes-voz-a.png
adb shell input swipe 640 600 640 150 300; sleep 2
adb exec-out screencap -p > capturas/6-ajustes-voz-b.png
# el carro «se durmió» 20 min: se adelanta el reloj y debe saludar solo (detector de suspensión)
adb shell am start -n $A --es prueba pref:greet_last=; sleep 2
J=$(TZ=Africa/Cairo date -d '+20 min' +%m%d%H%M%Y.%S); echo "salto a $J"
adb shell date "$J" || true
sleep 45
adb logcat -d | grep -E "arranque detectado|ElsadekVoz: (Buenos|صباح|مساء)" | tail -4 > capturas/arranque.txt
# comandos de voz (el texto como si lo hubiera reconocido Google)
adb shell am start -n $A --es prueba "'comando:الساعة كام'"; sleep 6
adb shell am start -n $A --es prueba "'comando:شغل إذاعة القرآن الكريم'"; sleep 25
adb shell am start -n $A --es prueba "'comando:وقف الراديو'"; sleep 4
adb shell am start -n $A --es prueba "'comando:افتح Chrome'"; sleep 6
adb exec-out screencap -p > capturas/10-comando-abrir.png
adb shell am start -n $A --es prueba inicio; sleep 3
echo "mapa egipto: $(curl -sI -m 30 https://download.mapsforge.org/maps/v5/africa/egypt.map | grep -iE '^HTTP|content-length' | tr -d '\r' | tr '\n' ' ')" > capturas/mapa.txt
# radio por internet: lista de Egipto, reproduce la primera y la ventana
adb shell am start -n $A --es prueba radio-demo; sleep 30
adb shell dumpsys media_session | grep -E "ElsadekRadio|state=PlaybackState" | head -6 > capturas/radio-sesion.txt
adb shell am start -n $A --es prueba inicio; shot 7a-radio-inicio 4
adb shell am start -n $A --es prueba radio; shot 7-radio 5
adb shell input keyevent KEYCODE_BACK
# cuadro del odómetro: los tres modos
adb shell am start -n $A --es prueba inicio; shot 11-odo-total 3
adb shell am start -n $A --es prueba pref:odo_view=1; shot 11-odo-viaje 3
adb shell am start -n $A --es prueba pref:odo_view=2; shot 11-odo-aceite 3
adb shell am start -n $A --es prueba pref:odo_view=0; sleep 2
# temas: «Azul marino y oro» en el inicio y la galería en Ajustes → Apariencia
adb shell am start -n $A --es prueba pref:palette=1; shot 8-tema-inicio 6
adb shell am start -n $A --es prueba ajustes:5; shot 9-temas 5
adb shell am start -n $A --es prueba pref:palette=0; sleep 3
adb logcat -d | grep -E "ElsadekVoz|voz:" | tail -30 > capturas/voz.txt
# viaje terminado → se sincroniza con Firebase (car_log_test en la versión de prueba)
adb shell am start -n $A --es prueba viaje-cerrar; sleep 20
DB=https://my-app-sync-11ad9-default-rtdb.europe-west1.firebasedatabase.app
curl -s "$DB/car_log_test.json" > capturas/firebase.json; cat capturas/firebase.json; echo
# se borra la prueba (las reglas no dejan vaciar una app entera: queda «probe» como ancla)
curl -s -X DELETE "$DB/car_log_test/docs.json" > /dev/null
curl -s -X DELETE "$DB/car_log_test/state.json" > /dev/null
adb shell am start -n $A --es prueba inicio; sleep 3
adb shell wm size 720x1280; adb shell wm density 240
adb shell am start -n $A --es prueba inicio; shot 5-vertical 10
adb shell wm size reset; adb shell wm density reset
# pantalla dividida (al final: necesita reiniciar con las ventanas libres activadas)
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done; sleep 20
adb shell am start -n $A --es prueba inicio; sleep 6
adb shell am start -n $A --es prueba dividir-demo; sleep 10
adb exec-out screencap -p > capturas/12-dividida.png
adb shell am start -n $A --es prueba inicio; sleep 3
adb shell am start -n $A --es prueba pref:split_ratio=33; sleep 2
adb shell am start -n $A --es prueba dividir-demo; sleep 8
adb exec-out screencap -p > capturas/12-dividida-tercio.png
{ adb logcat -d | grep -E "ElsadekSplit" | tail -3; adb shell am stack list; } > capturas/dividida.txt 2>&1
adb logcat -d | grep -E "FATAL|AndroidRuntime" | tail -60 > capturas/errores.txt
adb shell dumpsys package $P | grep -E "versionName|firstInstall" > capturas/paquete.txt
cat capturas/errores.txt
