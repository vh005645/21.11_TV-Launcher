#!/usr/bin/env bash
# Build vn.herotran.launcher bằng SDK có sẵn (không cần Gradle, không cần mạng).
# Kết quả: build/tvlauncher.apk (đã ký bằng vn-hero-sign.jks)
set -euo pipefail

SDK="/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
KEYSTORE="../vn-hero-sign.jks"
ALIAS="vnhero"
PASS="android"

cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/classes"

# 1. Biên dịch resource (nếu có) rồi liên kết với manifest
LINK_RES=""
if [ -d res ]; then
  mkdir -p "$OUT/compiled"
  "$BT/aapt2.exe" compile --dir res -o "$OUT/compiled/res.zip"
  LINK_RES="$OUT/compiled/res.zip"
fi
"$BT/aapt2.exe" link -o "$OUT/base.apk" -I "$PLAT" --manifest AndroidManifest.xml $LINK_RES

# 2. Biên dịch Java
find src -name "*.java" > "$OUT/sources.txt"
javac --release 8 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"

# 3. Đóng gói bytecode thành classes.dex
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$PLAT" \
  --output "$OUT" $(find "$OUT/classes" -name "*.class")

# 4. Nhúng classes.dex vào APK
python - "$OUT" <<'EOF'
import sys, zipfile, shutil, os
out = sys.argv[1]
src = os.path.join(out, "base.apk")
dst = os.path.join(out, "unsigned.apk")
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(os.path.join(out, "classes.dex"), "classes.dex")
EOF

# 5. Canh lề và ký
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --out "$OUT/tvlauncher.apk" "$OUT/aligned.apk"
java -jar "$BT/lib/apksigner.jar" verify "$OUT/tvlauncher.apk"
echo "Built: $(pwd)/$OUT/tvlauncher.apk"
