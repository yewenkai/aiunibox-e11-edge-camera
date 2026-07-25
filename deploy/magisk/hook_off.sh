#!/system/bin/sh

VIEW_DIR=/data/local/tmp/e11_edge_camera_viewers
MARKER=/sdcard/e11_edge_camera_watching
READER_KEY="${MTX_READER_TYPE}_${MTX_READER_ID}"

rm -f "$VIEW_DIR/$READER_KEY"
if ls "$VIEW_DIR" 2>/dev/null | grep -q .; then
    echo 1 > "$MARKER"
else
    echo 0 > "$MARKER"
fi
