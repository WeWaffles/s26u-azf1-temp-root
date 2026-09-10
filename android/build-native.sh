#!/bin/sh
set -eu

# Usage: build-native.sh [AZG3|AZF1]  (default AZG3: original SM-S948N target)
TARGET=${1:-AZG3}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
exploit_root="$repo_root/exploit"
sdk_root=${ANDROID_HOME:-"$HOME/Android/Sdk"}
ndk_root=${ANDROID_NDK_HOME:-"$sdk_root/ndk/29.0.14206865"}
compiler="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"

case "$TARGET" in
  AZG3)
    payload_dir="m3q-BP4A.251205.006"
    vendor_target="m3q-S948NKSS4AZG3"
    out_dir="build/m3q-BP4A.251205.006/bin"
    helper_defs=""
    ;;
  AZF1)
    payload_dir="m3q-S948USQS3AZF1"
    vendor_target="m3q-S948USQS3AZF1"
    out_dir="build/m3q-S948USQS3AZF1/bin"
    helper_defs="-DSOCK_PATH=\"/data/local/tmp/temp_su_azf1.sock\" -DKSU_LOADER_PATH=\"/data/local/tmp/ksud-m3q-S948USQS3AZF1-kdp\" -DKSU_LOG_PATH=\"/data/local/tmp/m3q-azf1-kernelsu-late-load.log\" -DM3Q_OVERLAY_ROOT=\"/dev/temp_root_su_azf1\" -DM3Q_DAEMON_TRACE_PATH=\"/data/local/tmp/m3q-azf1-daemon.trace\""
    ;;
  *)
    echo "Unknown target: $TARGET (expected AZG3 or AZF1)" >&2
    exit 1
    ;;
esac

output_dir="$exploit_root/$out_dir"

if [ ! -x "$compiler" ]; then
    echo "Android NDK compiler not found: $compiler" >&2
    exit 1
fi

mkdir -p "$output_dir"
cd "$exploit_root"

# shellcheck disable=SC2086
"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror -Isrc -fPIE -pie \
    $helper_defs \
    src/su_daemon.c -ldl \
    -o "$out_dir/su_daemon_aarch64_pie.app"

"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror \
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
    -DAPP_PAYLOAD=1 -fPIC -Ivendor/root-my-galaxy/src \
    "-DTARGET_HEADER=\"targets/$vendor_target/target.h\"" \
    vendor/root-my-galaxy/src/main.c \
    vendor/root-my-galaxy/src/util.c \
    vendor/root-my-galaxy/src/slide_app.c \
    vendor/root-my-galaxy/src/fops.c \
    vendor/root-my-galaxy/src/pipe.c \
    vendor/root-my-galaxy/src/root.c \
    vendor/root-my-galaxy/src/preload.c \
    -shared -pthread \
    -o "$out_dir/slide_oracle.app.so"

"$compiler" \
    -O2 -g0 -Wall -Wextra -Werror \
    -Wno-unused-parameter -Wno-sign-compare -Wno-unused-function \
    -Isrc -fPIC \
    "-DTARGET_CONFIG_H=\"targets/$payload_dir/target.h\"" \
    "src/targets/$payload_dir/main.c" \
    "src/targets/$payload_dir/util.c" \
    "src/targets/$payload_dir/slide.c" \
    "src/targets/$payload_dir/fops.c" \
    "src/targets/$payload_dir/pipe.c" \
    src/faketables.c \
    src/stage3.c \
    "src/targets/$payload_dir/root.c" \
    src/app_preload.c \
    src/stage3_loop.S \
    src/stage3_poll.S \
    -shared -pthread \
    -o "$out_dir/preload.app.so"

sha256sum \
    "$out_dir/su_daemon_aarch64_pie.app" \
    "$out_dir/slide_oracle.app.so" \
    "$out_dir/preload.app.so"
