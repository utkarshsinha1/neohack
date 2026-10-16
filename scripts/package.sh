#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
APP_NAME=Feric
APP_VERSION=1.0.0
JAR_NAME=teacher-classroom-${APP_VERSION}.jar
DIST_DIR="$ROOT_DIR/dist"
INPUT_DIR="$ROOT_DIR/target/jpackage-input"
PNG_ICON="$ROOT_DIR/logos/feric-app-icon.png"

cd "$ROOT_DIR"
if [ -n "${JAVA_HOME:-}" ]; then
  RUNTIME_IMAGE=$JAVA_HOME
elif [ "$(uname -s)" = "Darwin" ]; then
  RUNTIME_IMAGE=$(/usr/libexec/java_home)
else
  printf 'JAVA_HOME must point to a full JDK installation.\n' >&2
  exit 1
fi
if [ ! -x "$RUNTIME_IMAGE/bin/java" ] || [ ! -x "$RUNTIME_IMAGE/bin/javac" ]; then
  printf 'The packaging runtime must be a full JDK: %s\n' "$RUNTIME_IMAGE" >&2
  exit 1
fi

mvn -B clean package
JPACKAGE_RUNTIME=$RUNTIME_IMAGE
if [ "$(uname -s)" = "Linux" ]; then
  JPACKAGE_RUNTIME="$ROOT_DIR/target/feric-runtime"
  "$RUNTIME_IMAGE/bin/jlink" \
    --add-modules java.desktop,java.net.http,jdk.compiler,jdk.crypto.ec \
    --output "$JPACKAGE_RUNTIME"
fi

mkdir -p "$DIST_DIR"
rm -rf "$INPUT_DIR" "$DIST_DIR/$APP_NAME" "$DIST_DIR/${APP_NAME}.app"
rm -f "$DIST_DIR/${APP_NAME}-macOS.zip" "$DIST_DIR/${APP_NAME}-Linux.zip"
mkdir -p "$INPUT_DIR"
cp "$ROOT_DIR/target/$JAR_NAME" "$INPUT_DIR/$JAR_NAME"
if [ "$(uname -s)" = "Darwin" ]; then
  xattr -cr "$INPUT_DIR"
  ICONSET="$ROOT_DIR/target/Feric.iconset"
  mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$PNG_ICON" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double_size=$((size * 2))
    sips -z "$double_size" "$double_size" "$PNG_ICON" \
      --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$ROOT_DIR/logos/feric.icns"
  PACKAGE_DEST=$(mktemp -d "${TMPDIR:-/tmp}/Feric.XXXXXX")
  trap 'rm -rf "$PACKAGE_DEST"' EXIT HUP INT TERM
  ICON="$ROOT_DIR/logos/feric.icns"
else
  PACKAGE_DEST="$DIST_DIR"
  if [ "$(uname -s)" = "Linux" ]; then
    ICON="$PNG_ICON"
  else
    ICON="$ROOT_DIR/logos/feric.ico"
  fi
fi

strip_finder_info() {
  if xattr -l "$1" | grep -q '^com.apple.FinderInfo:'; then
    xattr -d com.apple.FinderInfo "$1"
  fi
}

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$INPUT_DIR" \
  --main-jar "$JAR_NAME" \
  --main-class com.classroom.live.AppLauncher \
  --runtime-image "$JPACKAGE_RUNTIME" \
  --icon "$ICON" \
  --dest "$PACKAGE_DEST"

if [ "$(uname -s)" = "Darwin" ]; then
  codesign --verify --deep --strict "$PACKAGE_DEST/${APP_NAME}.app"
  ditto -c -k --sequesterRsrc --keepParent \
    "$PACKAGE_DEST/${APP_NAME}.app" \
    "$DIST_DIR/${APP_NAME}-macOS.zip"
  ditto "$PACKAGE_DEST/${APP_NAME}.app" "$DIST_DIR/${APP_NAME}.app"
  strip_finder_info "$DIST_DIR/${APP_NAME}.app"
else
  if [ "$(uname -s)" = "Linux" ]; then
    APP_RUNTIME="$DIST_DIR/$APP_NAME/lib/runtime"
    if [ ! -d "$APP_RUNTIME" ]; then
      printf 'jpackage did not create the Linux runtime directory: %s\n' "$APP_RUNTIME" >&2
      exit 1
    fi
    rm -rf "$APP_RUNTIME"
    cp -a "$RUNTIME_IMAGE" "$APP_RUNTIME"
  fi
  zip -qr "$DIST_DIR/${APP_NAME}-Linux.zip" "$DIST_DIR/$APP_NAME"
fi

printf 'App image created in %s\n' "$DIST_DIR"
