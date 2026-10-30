#!/usr/bin/env bash
# Сборка APK с Android SDK 34 и JDK. Модель в APK не включена.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS="${SDK_BUILD_TOOLS:-${SDK_ROOT}/build-tools/34.0.0}"
ANDROID_JAR="${ANDROID_JAR:-${SDK_ROOT}/platforms/android-34/android.jar}"
OUT="${OUT_DIR:-${ROOT}/build}"
if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt" || ! -x "$BUILD_TOOLS/d8" ]]; then
  echo "Нужны Android SDK Platform 34 и Build-Tools 34.0.0. Укажи ANDROID_SDK_ROOT." >&2; exit 1
fi
for command in javac keytool; do command -v "$command" >/dev/null || { echo "Не найдена программа: $command" >&2; exit 1; }; done
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
WORK="$OUT/work"
rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/apk/lib/arm64-v8a"
"$BUILD_TOOLS/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -A "$ROOT/assets" -I "$ANDROID_JAR" -F "$WORK/unsigned.apk"
mapfile -t sources < <(find "$ROOT/src" -name '*.java' | sort)
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" "${sources[@]}"
mapfile -t classes < <(find "$WORK/classes" -name '*.class' | sort)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 28 --output "$WORK/dex" "${classes[@]}"
cp "$WORK/dex/classes.dex" "$WORK/apk/classes.dex"
cp "$ROOT"/native/arm64-v8a/*.so "$WORK/apk/lib/arm64-v8a/"
(cd "$WORK/apk" && "$BUILD_TOOLS/aapt" add "$WORK/unsigned.apk" classes.dex lib/arm64-v8a/*.so >/dev/null)
"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
KEYSTORE="${SIGNING_KEYSTORE:-$OUT/local-debug.keystore}"
KEY_ALIAS="${SIGNING_ALIAS:-androiddebugkey}"
KEY_PASS="${SIGNING_PASSWORD:-android}"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -dname 'CN=AI Chat Models Local Build, O=Local Build, C=US' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  echo "Создан локальный ключ подписи: $KEYSTORE"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" --out "$OUT/AI_Chat_Models.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUT/AI_Chat_Models.apk"
echo "Готово: $OUT/AI_Chat_Models.apk"
