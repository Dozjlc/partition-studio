#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
SDK="${ANDROID_SDK:-/opt/android-sdk}"
AJAR="$SDK/platforms/android-35/android.jar"
RESJAR="$SDK/platforms/android-28/android.jar"
AAPT2="${AAPT2:-/usr/bin/aapt2}"
R8JAR="$SDK/build-tools/35.0.0/lib/d8.jar"
ZIPALIGN="${ZIPALIGN:-/usr/bin/zipalign}"
APKSIGNER="${APKSIGNER:-/usr/bin/apksigner}"
KEYSTORE="${KEYSTORE:-$ROOT/your-release.jks}"
KS_PASS="${KS_PASS:-}"
KEY_ALIAS="${KEY_ALIAS:-partitionstudio}"

if [ -z "$KS_PASS" ] || [ ! -f "$KEYSTORE" ]; then
  echo "请设置 KEYSTORE 和 KS_PASS 环境变量，并准备自己的签名文件。"
  echo "源码不包含任何 keystore。"
  exit 1
fi

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/r8" "$OUT/res.zip" "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk" "$OUT/分区镜像-v1.8.apk"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/r8"

"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/app-unsigned.apk" \
  -I "$RESJAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay \
  "$OUT/res.zip"

find "$APP/src" "$OUT/gen" -name '*.java' -print > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$AJAR:$SDK/build-tools/35.0.0/core-lambda-stubs.jar" -classpath "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

find "$OUT/classes" -name '*.class' -print > "$OUT/classes.txt"
java -cp "$R8JAR" com.android.tools.r8.R8 \
  --release --min-api 26 \
  --lib "$AJAR" \
  --output "$OUT/r8" \
  --pg-conf "$ROOT/proguard-rules.pro" \
  --pg-map-output "$OUT/mapping.txt" \
  @"$OUT/classes.txt"

test -f "$OUT/r8/classes.dex"
(cd "$OUT/r8" && zip -q -9 "$OUT/app-unsigned.apk" classes.dex)

# Build an encrypted manifest of the final DEX and resource table hashes.
python3 - "$OUT/app-unsigned.apk" "$OUT/i.bin" <<'PYHASH'
import hashlib, sys, zipfile
apk, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    dex = hashlib.sha256(z.read('classes.dex')).hexdigest()
    res = hashlib.sha256(z.read('resources.arsc')).hexdigest()
    manifest = hashlib.sha256(z.read('AndroidManifest.xml')).hexdigest()
    qr = hashlib.sha256(z.read('assets/d.bin')).hexdigest()
payload = ('\n'.join([dex, res, manifest, qr])).encode('utf-8')
key = 0x6D
open(out, 'wb').write(bytes(b ^ key for b in payload))
PYHASH
mkdir -p "$OUT/assets"
cp -f "$OUT/i.bin" "$OUT/assets/i.bin"
(cd "$OUT" && zip -q -9 "$OUT/app-unsigned.apk" assets/i.bin)

"$ZIPALIGN" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/分区镜像-v1.8.apk" "$OUT/app-aligned.apk"

"$APKSIGNER" verify --verbose "$OUT/分区镜像-v1.8.apk"

# Fail the build if the pinned cert or payload hashes do not match the signed APK.
CERT_SHA="$(/usr/bin/apksigner verify --print-certs "$OUT/分区镜像-v1.8.apk" \
  | awk -F': ' '/SHA-256 digest/{gsub(/:/,"",$2); print tolower($2); exit}')"
PIN_SHA="$(grep -oE '[0-9a-f]{64}' "$APP/src/com/dsh/partitionstudio/Integrity.java" | head -1)"
test -n "$CERT_SHA" && test "$CERT_SHA" = "$PIN_SHA" || {
  echo "ERROR: signer pin mismatch: cert=$CERT_SHA pin=$PIN_SHA"; exit 1; }
python3 - "$OUT/分区镜像-v1.8.apk" <<'PYVERIFY'
import hashlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    expected = bytes(b ^ 0x6D for b in z.read('assets/i.bin')).decode().split('\n')
    names = ['classes.dex', 'resources.arsc', 'AndroidManifest.xml', 'assets/d.bin']
    for i, name in enumerate(names):
        if hashlib.sha256(z.read(name)).hexdigest() != expected[i]:
            raise SystemExit('ERROR: integrity hash mismatch: ' + name)
print('signer/hash pins verified')
PYVERIFY
ls -lh "$OUT/分区镜像-v1.8.apk"
