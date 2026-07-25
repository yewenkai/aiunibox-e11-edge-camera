#!/system/bin/sh
# AIUniBOX-E11 Edge Camera 自启脚本（Magisk service.d，开机后 root 执行）
BIN_DIR=/data/local/tmp/bin
LOG=/data/local/tmp/e11_camera_service.log
MEDIAMTX_LOG=/data/local/tmp/e11_camera_mediamtx.log
WATCHDOG_PID=/data/local/tmp/e11_camera_watchdog.pid
APP_PKG=org.e11camera.edge
TOKEN_FILE=/data/local/tmp/e11-edge-camera/api_token

log() { echo "$(date '+%m-%d %H:%M:%S') $1" >> "$LOG"; }

start_monitor_app() {
    # 部分定制系统禁止以 Service 作为第三方 App 的冷启动入口。
    # 先启动 Activity 让 App 进程创建前台 Service，成功后回到桌面。
    am start -n "$APP_PKG/.MainActivity" >> "$LOG" 2>&1
    sleep 5
    input keyevent HOME
}

# 防止 Magisk 重载或手动执行时生成多个守护循环。
if [ -f "$WATCHDOG_PID" ]; then
    OLD_PID=$(cat "$WATCHDOG_PID" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$WATCHDOG_PID"
trap 'rm -f "$WATCHDOG_PID"' EXIT

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

# 1) 通过 Activity 冷启动，服务建立后自动回到桌面。
sleep 5
log "启动监控 App: $APP_PKG"
start_monitor_app
sleep 8

# 2) mediamtx 单进程守护：进程退出后由本循环重启。
(
    while true; do
        log "启动 mediamtx"
        "$BIN_DIR/mediamtx" "$BIN_DIR/mediamtx.yml" >> "$MEDIAMTX_LOG" 2>&1
        log "mediamtx 已退出，3 秒后重启"
        sleep 3
    done
) &

# 3) App 与内置 RTSP 直推健康检查。连续失败时重建 App 进程。
FAIL_COUNT=0
while true; do
    TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)
    STATUS_URL=http://127.0.0.1:8080/api/status
    [ -n "$TOKEN" ] && STATUS_URL="$STATUS_URL?token=$TOKEN"
    STATUS=$(/system/bin/busybox wget -qO- "$STATUS_URL" 2>/dev/null)
    if echo "$STATUS" | grep -q '"streamReady":true' &&
        echo "$STATUS" | grep -q '"rtspPublishing":true'; then
        FAIL_COUNT=0
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        log "App 健康检查失败 ($FAIL_COUNT/2)"
        if [ "$FAIL_COUNT" -ge 2 ]; then
            log "重启监控 App"
            am force-stop "$APP_PKG"
            sleep 2
            start_monitor_app
            FAIL_COUNT=0
        fi
    fi
    sleep 10
done
