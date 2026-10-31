#!/usr/bin/env bash
# Builds Shade Widget without Gradle: aapt (resources), javac, dx, zipalign, apksigner.
set -euo pipefail
cd "$(dirname "$0")"

RES_JAR=/opt/asdk/android-34.jar   # resource linking (newest the packaged aapt can read)
API_JAR=/opt/asdk/android-36.jar   # Java compile target (Android 16 APIs)
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

echo "== resources"
aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$RES_JAR" \
     --auto-add-overlay -F "$OUT/unsigned.apk"

echo "== javac"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
      -bootclasspath "$API_JAR" -d "$OUT/classes" \
      $(find src "$OUT/gen" -name '*.java')

echo "== dex"
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

echo "== package"
( cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null )
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "== sign"
KS=release.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass shadewidget -keypass shadewidget \
          -alias shadewidget -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Shade Widget, O=Giorgos" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:shadewidget --key-pass pass:shadewidget \
          --min-sdk-version 34 --out "$OUT/ShadeWidget.apk" "$OUT/aligned.apk"
apksigner verify --verbose "$OUT/ShadeWidget.apk" | head -5
ls -la "$OUT/ShadeWidget.apk"
