#!/system/bin/sh
# root hooks 共用：只读 app 创建的 marker，原位更新，不创建 app 路径或修改权限。
E11_VIEW_DIR=${E11_VIEW_DIR:-/data/local/tmp/e11_edge_camera_viewers}
E11_BIN_DIR=${E11_BIN_DIR:-/data/local/tmp/bin}
E11_MARKER=${E11_MARKER:-/data/user/0/org.e11camera.edge/files/viewer-state/watchers}
E11_TOKEN_FILE=${E11_TOKEN_FILE:-/data/local/tmp/e11-edge-camera/api_token}
E11_GENERATION_FILE="$E11_VIEW_DIR/generation"
E11_HTTP_KIND=
E11_HTTP_TOOL=
E11_LOCK_BUSYBOX=

e11_find_tools() {
    for e11_tool_candidate in /data/adb/magisk/busybox /system/bin/busybox; do
        [ -x "$e11_tool_candidate" ] || continue
        if [ -z "$E11_LOCK_BUSYBOX" ]; then
            e11_tool_help=$("$e11_tool_candidate" flock --help 2>&1)
            case "$e11_tool_help" in
                *'Usage: flock'*) E11_LOCK_BUSYBOX=$e11_tool_candidate ;;
            esac
        fi
        [ -z "$E11_HTTP_KIND" ] || continue
        e11_tool_help=$("$e11_tool_candidate" wget --help 2>&1)
        case "$e11_tool_help" in
            *'--header'*) E11_HTTP_KIND=busybox; E11_HTTP_TOOL=$e11_tool_candidate ;;
        esac
    done
    if [ -z "$E11_HTTP_KIND" ]; then
        e11_curl_candidate=$(command -v curl 2>/dev/null)
        if [ -n "$e11_curl_candidate" ] && [ -x "$e11_curl_candidate" ]; then
            E11_HTTP_KIND=curl
            E11_HTTP_TOOL=$e11_curl_candidate
        fi
    fi
    # 某些新固件提供 toybox wget。仅在实际支持 Bearer header 时使用。
    if [ -z "$E11_HTTP_KIND" ] && [ -x /system/bin/toybox ]; then
        e11_tool_help=$(/system/bin/toybox wget --help 2>&1)
        case "$e11_tool_help" in
            *'--header'*) E11_HTTP_KIND=toybox; E11_HTTP_TOOL=/system/bin/toybox ;;
        esac
    fi
}

e11_api_get() {
    e11_request_path=$1
    e11_request_token=
    if [ -r "$E11_TOKEN_FILE" ]; then
        IFS= read -r e11_request_token < "$E11_TOKEN_FILE"
    fi
    case "$E11_HTTP_KIND" in
        busybox|toybox)
            if [ -n "$e11_request_token" ]; then
                "$E11_HTTP_TOOL" wget -q -T 3 -O - \
                    --header "Authorization: Bearer $e11_request_token" \
                    "http://127.0.0.1:8080$e11_request_path" 2>/dev/null
            else
                "$E11_HTTP_TOOL" wget -q -T 3 -O - \
                    "http://127.0.0.1:8080$e11_request_path" 2>/dev/null
            fi
            ;;
        curl)
            if [ -n "$e11_request_token" ]; then
                "$E11_HTTP_TOOL" -fsS --connect-timeout 2 --max-time 3 \
                    -H "Authorization: Bearer $e11_request_token" \
                    "http://127.0.0.1:8080$e11_request_path" 2>/dev/null
            else
                "$E11_HTTP_TOOL" -fsS --connect-timeout 2 --max-time 3 \
                    "http://127.0.0.1:8080$e11_request_path" 2>/dev/null
            fi
            ;;
        *) return 1 ;;
    esac
}

e11_generation_is_current() {
    [ -n "$E11_MEDIA_GENERATION" ] && [ -r "$E11_GENERATION_FILE" ] || return 1
    IFS= read -r e11_generation_current < "$E11_GENERATION_FILE"
    [ "$E11_MEDIA_GENERATION" = "$e11_generation_current" ]
}

e11_write_marker() (
    e11_marker_value=$1
    [ -f "$E11_MARKER" ] || exit 0
    # /proc/uptime 和 Android elapsedRealtime 使用同一启动时间基准；向下取整到秒。
    read -r e11_uptime e11_uptime_idle < /proc/uptime || exit 1
    e11_uptime_seconds=${e11_uptime%%.*}
    case "$e11_uptime_seconds" in ''|*[!0-9]*) exit 1 ;; esac
    e11_uptime_ms=$((e11_uptime_seconds * 1000))
    # 先以不带 O_CREAT 的只读方式打开，再通过 fd 写原 inode。
    # 即使 marker 在检查后被删除，也不会在 app 目录新建 root 所有的文件。
    exec 9< "$E11_MARKER" || exit 0
    printf '%s %s\n' "$e11_marker_value" "$e11_uptime_ms" > /proc/self/fd/9
)

e11_publish_viewers() {
    e11_has_viewers=0
    for e11_reader_file in "$E11_VIEW_DIR"/reader_*; do
        if [ -f "$e11_reader_file" ]; then
            e11_has_viewers=1
            break
        fi
    done
    e11_write_marker "$e11_has_viewers"
}

e11_with_view_lock() (
    # Android mksh 给 exec N> 的 fd 设置 close-on-exec，子进程不能 flock N。
    # 由 BusyBox 自己打开锁文件并启动白名单 worker，在退出/异常时自动释放。
    [ -n "$E11_LOCK_BUSYBOX" ] || exit 1
    mkdir -p "$E11_VIEW_DIR" || exit 1
    E11_VIEW_DIR="$E11_VIEW_DIR" E11_MARKER="$E11_MARKER" \
        E11_TOKEN_FILE="$E11_TOKEN_FILE" E11_BIN_DIR="$E11_BIN_DIR" \
        E11_MEDIA_GENERATION="${E11_MEDIA_GENERATION:-}" \
        MTX_READER_TYPE="${MTX_READER_TYPE:-}" MTX_READER_ID="${MTX_READER_ID:-}" \
        "$E11_LOCK_BUSYBOX" flock -x "$E11_VIEW_DIR/.lock" \
        /system/bin/sh "$E11_BIN_DIR/camera_common.sh" --locked "$@"
)

e11_refresh_viewers() {
    e11_with_view_lock e11_publish_viewers
}

e11_update_reader_locked() {
    e11_generation_is_current || return 0
    e11_reader_key="${MTX_READER_TYPE}_${MTX_READER_ID}"
    # 只接受 MediaMTX 生成的 reader 类型和 UUID，不能把环境值当任意路径。
    case "$e11_reader_key" in ''|_|*[!a-zA-Z0-9_-]*) return 1 ;; esac
    if [ "$1" = 1 ]; then
        : > "$E11_VIEW_DIR/reader_$e11_reader_key"
    else
        rm -f "$E11_VIEW_DIR/reader_$e11_reader_key"
    fi
    e11_publish_viewers
}

e11_update_reader() {
    e11_with_view_lock e11_update_reader_locked "$1"
}

e11_reset_viewers_locked() {
    rm -f "$E11_VIEW_DIR"/reader_*
    printf '%s\n' "$E11_MEDIA_GENERATION" > "$E11_GENERATION_FILE"
    e11_write_marker 0
}

e11_reset_viewers() {
    e11_with_view_lock e11_reset_viewers_locked
}

e11_find_tools

if [ "${1:-}" = --locked ]; then
    shift
    case "${1:-}" in
        e11_publish_viewers|e11_reset_viewers_locked)
            [ "$#" -eq 1 ] || exit 2
            "$1"
            ;;
        e11_update_reader_locked)
            [ "$#" -eq 2 ] || exit 2
            case "$2" in 0|1) "$1" "$2" ;; *) exit 2 ;; esac
            ;;
        *) exit 2 ;;
    esac
    exit "$?"
fi
