#!/usr/bin/env bash
set -euo pipefail
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_QWDTT_REF=//p' nb4a.properties | tr -d '\r[:space:]')
[[ "$REF" =~ ^[0-9a-f]{40}$ ]]
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/qwdtt145.XXXXXX")
git init -q "$SOURCE"
git -C "$SOURCE" fetch -q --depth=1 https://github.com/ttrroyy/Rawline.git "$REF"
git -C "$SOURCE" checkout -q --detach FETCH_HEAD
test "$(git -C "$SOURCE" rev-parse HEAD)" = "$REF"
python3 scripts/prepare_qwdtt145.py "$SOURCE/qwdtt"
python3 scripts/prepare_csqtt_compat_bridge.py "$SOURCE/qwdtt"
(cd "$SOURCE/qwdtt"; gofmt -w .; CGO_ENABLED=0 go test -run 'TestMobileSOCKS|TestPrivateBridgeConfig' -count=1 .)
NDK="$ANDROID_HOME/ndk/28.2.13676358"
if [ ! -d "$NDK" ]; then
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install 'ndk;28.2.13676358' > /dev/null || test -d "$NDK"
fi
for ABI in arm64-v8a armeabi-v7a x86_64; do
  case "$ABI" in
    arm64-v8a) ARCH=arm64; CLANG=aarch64-linux-android ;;
    armeabi-v7a) ARCH=arm; CLANG=armv7a-linux-androideabi ;;
    x86_64) ARCH=amd64; CLANG=x86_64-linux-android ;;
  esac
  OUT="$ROOT/deployment/qwdtt145/$ABI"
  mkdir -p "$OUT"
  (cd "$SOURCE/qwdtt"; GOOS=android GOARCH="$ARCH" GOARM=7 CGO_ENABLED=1 \
    CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang" \
    go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libqwdtt.so" .)
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$OUT/libqwdtt.so" | grep -q 'Type:.*DYN'
  printf 'Rawline %s\nAndroid ABI %s\nqWDTT upstream 1.4.5\n' "$REF" "$ABI" > "$OUT/SOURCES.txt"
done
