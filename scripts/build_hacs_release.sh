#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
COMPONENT_DIR="$REPO_ROOT/custom_components/aiunibox_e11"
OUTPUT=${1:-"$REPO_ROOT/aiunibox_e11.zip"}

case "$OUTPUT" in
    /*) ;;
    *) OUTPUT="$PWD/$OUTPUT" ;;
esac

rm -f "$OUTPUT"
(
    cd "$COMPONENT_DIR"
    zip -qr "$OUTPUT" . \
        -x '__pycache__/*' '*.pyc' '.DS_Store'
)

# ZIP 根目录应直接是集成文件，只允许 brand/ 和 translations/ 两个子目录。
unzip -Z1 "$OUTPUT" | awk '
    /\/$/ { next }
    /^[^\/]+$/ { next }
    /^translations\/[^\/]+$/ { next }
    /^brand\/[^\/]+$/ { next }
    { print "unexpected path: " $0 > "/dev/stderr"; invalid = 1 }
    END { exit invalid }
'
unzip -Z1 "$OUTPUT" | grep -qx 'manifest.json'
unzip -Z1 "$OUTPUT" | grep -qx '__init__.py'

printf 'HACS release package ready: %s\n' "$OUTPUT"
