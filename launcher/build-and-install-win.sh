#!/usr/bin/env bash
# Windows (Git Bash) version of build-and-install.sh: portable JDK + SDK in ../../toolchain, Google's adb.exe.
#   launcher/build-and-install-win.sh                 build + install (with the nature wallpapers)
#   WALLPAPERS=0 launcher/build-and-install-win.sh    plain dark gradient instead
#   launcher/build-and-install-win.sh --build         build only
set -euo pipefail
cd "$(dirname "$0")"
TC="${TOOLCHAIN:-../../toolchain}"
export JAVA_HOME="${JAVA_HOME:-$TC/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$TC/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
ADB="../platform-tools/adb.exe -s ${TV_HOST:-$(cat ../tools/.tv_host)}:5555"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets"
[ "${WALLPAPERS:-1}" = 1 ] && cp -r assets/. "$B/assets/"
"$BT/aapt2.exe" compile --dir res -o "$B/res.zip"
"$BT/aapt2.exe" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
javac -encoding UTF-8 -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8.bat" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
jar -u -M -f "$B/unsigned.apk" -C "$B/dex" classes.dex
# Assets go in with jar, not aapt2 -A: Windows aapt2 stores them as assets/wallpapers\01.jpg, which Android can't list.
jar -u -M -f "$B/unsigned.apk" -C "$B" assets
"$BT/zipalign.exe" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
[ "${1:-}" = "--build" ] && exit 0

# One pm call per adb shell call: chained pm commands can stop after the first.
$ADB install -r "$B/tvhome.apk"
for c in \
  "pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION" \
  "pm grant com.mihir.tvhome android.permission.READ_EXTERNAL_STORAGE" \
  "pm grant com.mihir.tvhome android.permission.READ_MEDIA_IMAGES" \
  "mkdir -p /sdcard/Pictures/Wallpapers" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "pm disable-user --user 0 $STOCK_LAUNCHER" \
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith" \
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity" \
  "am force-stop com.mihir.tvhome" \
  "am force-stop com.android.tv.settings" \
  "input keyevent KEYCODE_HOME"; do
  echo "\$ $c"; $ADB shell "$c"
done
echo "Installed. Press Home on the remote."
