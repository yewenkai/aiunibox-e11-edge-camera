#!/system/bin/sh

VIEW_DIR=/data/local/tmp/e11_edge_camera_viewers
MARKER=/sdcard/e11_edge_camera_watching
READER_KEY="${MTX_READER_TYPE}_${MTX_READER_ID}"

mkdir -p "$VIEW_DIR"
touch "$VIEW_DIR/$READER_KEY"
echo 1 > "$MARKER"
