#!/system/bin/sh
# AIUniBOX-E11 Edge Camera 自启脚本（Magisk service.d，开机后 root 执行）
BIN_DIR=/data/local/tmp/bin
LOG=/data/local/tmp/e11_camera_service.log
MEDIAMTX_LOG=/data/local/tmp/e11_camera_mediamtx.log
STATE_DIR=/data/local/tmp/e11-edge-camera
WATCHDOG_STATE="$STATE_DIR/watchdog.state"
LEGACY_WATCHDOG_PID=/data/local/tmp/e11_camera_watchdog.pid
APP_PKG=org.e11camera.edge
LEGACY_APP_PKG=com.unicom.ptzmonitor
TOKEN_FILE="$STATE_DIR/api_token"
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
[ -n "$BOOT_ID" ] || BOOT_ID=$(awk '/^btime / { print $2 }' /proc/stat 2>/dev/null)
MEDIAMTX_GUARD_PID=

log() { echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOG"; }

pid_is_our_watchdog() {
    PID=$1
    [ -n "$PID" ] && [ -r "/proc/$PID/cmdline" ] || return 1
    CMDLINE=$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)
    case "$CMDLINE" in
        *ptz_rtsp/service.sh*|*aiunibox-e11-edge-camera.sh*) return 0 ;;
    esac
    return 1
}

stop_mediamtx() {
    for PID in $(pidof mediamtx 2>/dev/null); do
        kill "$PID" 2>/dev/null
    done
}

media_port_ready() {
    # 部分定制固件的 nc 不支持 -z，即使端口已监听也会返回失败。
    # ss 来自 Android toybox/iproute2，在本设备上可稳定读取监听表。
    ss -lnt 2>/dev/null | grep -q ':8554[[:space:]]'
}

start_monitor_app() {
    # 部分定制系统禁止以 Service 作为第三方 App 的冷启动入口。
    # 先启动 Activity 让 App 进程创建前台 Service，成功后回到桌面。
    am force-stop "$LEGACY_APP_PKG" >/dev/null 2>&1
    am start -n "$APP_PKG/.MainActivity" >> "$LOG" 2>&1
    sleep 5
    input keyevent HOME
}

# PID 会跨重启复用。只有 boot ID、PID 和进程命令行同时匹配，才视为已有实例。
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
if [ -f "$WATCHDOG_STATE" ]; then
    read OLD_BOOT_ID OLD_PID < "$WATCHDOG_STATE"
    if [ "$OLD_BOOT_ID" = "$BOOT_ID" ] &&
        kill -0 "$OLD_PID" 2>/dev/null &&
        pid_is_our_watchdog "$OLD_PID"; then
        exit 0
    fi
    log "清理失效的 watchdog 状态: boot=$OLD_BOOT_ID pid=$OLD_PID"
fi

# 兼容清理 v1.1.0 及更早版本遗留的纯 PID 文件。
if [ -f "$LEGACY_WATCHDOG_PID" ]; then
    LEGACY_PID=$(cat "$LEGACY_WATCHDOG_PID" 2>/dev/null)
    log "清理旧版 watchdog PID 文件: pid=$LEGACY_PID"
fi
rm -f "$WATCHDOG_STATE" "$LEGACY_WATCHDOG_PID"
umask 077
echo "$BOOT_ID $$" > "$WATCHDOG_STATE"

cleanup() {
    [ -n "$MEDIAMTX_GUARD_PID" ] && kill "$MEDIAMTX_GUARD_PID" 2>/dev/null
    stop_mediamtx
    rm -f "$WATCHDOG_STATE" "$LEGACY_WATCHDOG_PID"
}
trap cleanup EXIT
trap 'exit 0' INT TERM

log "===== AIUniBOX-E11 Edge Camera 自启脚本开始 ====="
mkdir -p "$BIN_DIR"
rm -rf /data/local/tmp/e11_edge_camera_viewers
mkdir -p /data/local/tmp/e11_edge_camera_viewers
echo 0 > /sdcard/e11_edge_camera_watching

# 等待系统启动完成
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
log "系统启动完成"

# 等待网络
for i in $(seq 1 30); do
    IP=$(ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1)
    [ -n "$IP" ] && { log "网络就绪: $IP"; break; }
    sleep 2
done

# 1) MediaMTX 单进程守护：先清理孤儿进程，退出后由本循环重启。
stop_mediamtx
(
    while true; do
        if [ ! -x "$BIN_DIR/mediamtx" ] || [ ! -r "$BIN_DIR/mediamtx.yml" ]; then
            log "MediaMTX 文件缺失或权限错误，3 秒后重试"
            sleep 3
            continue
        fi
        log "启动 MediaMTX"
        "$BIN_DIR/mediamtx" "$BIN_DIR/mediamtx.yml" >> "$MEDIAMTX_LOG" 2>&1
        log "mediamtx 已退出，3 秒后重启"
        sleep 3
    done
) &
MEDIAMTX_GUARD_PID=$!

for i in $(seq 1 20); do
    media_port_ready && { log "MediaMTX RTSP 端口就绪"; break; }
    sleep 1
done

# 2) 禁用旧应用，避免定制桌面在重启后恢复它并抢占摄像头和 8080。
pm enable --user 0 "$APP_PKG" >> "$LOG" 2>&1
am force-stop "$LEGACY_APP_PKG" >/dev/null 2>&1
pm disable-user --user 0 "$LEGACY_APP_PKG" >> "$LOG" 2>&1

# 3) 通过 Activity 冷启动，服务建立后自动回到桌面。
sleep 2
log "启动监控 App: $APP_PKG"
start_monitor_app
sleep 8

# 4) 同时检查正确的 App 进程、MediaMTX 端口和 RTSP 发布状态。
# 连续失败时重建 App；MediaMTX 异常时先触发其守护循环重启。
FAIL_COUNT=0
while true; do
    TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)
    STATUS_URL=http://127.0.0.1:8080/api/status
    [ -n "$TOKEN" ] && STATUS_URL="$STATUS_URL?token=$TOKEN"
    STATUS=$(/system/bin/busybox wget -qO- "$STATUS_URL" 2>/dev/null)
    if pidof "$APP_PKG" >/dev/null 2>&1 &&
        media_port_ready &&
        echo "$STATUS" | grep -q '"streamReady":true' &&
        echo "$STATUS" | grep -q '"rtspPublishing":true'; then
        FAIL_COUNT=0
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        log "App 健康检查失败 ($FAIL_COUNT/2)"
        if [ "$FAIL_COUNT" -ge 2 ]; then
            if ! media_port_ready; then
                log "MediaMTX 端口异常，触发重启"
                stop_mediamtx
                sleep 3
            fi
            log "重启监控 App"
            am force-stop "$APP_PKG"
            sleep 2
            start_monitor_app
            FAIL_COUNT=0
        fi
    fi
    sleep 10
done
