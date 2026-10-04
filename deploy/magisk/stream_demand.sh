#!/system/bin/sh
# MediaMTX runOnDemand：先唤醒允许采集的 app，再在读者连接期间续租。
BIN_DIR=${E11_BIN_DIR:-/data/local/tmp/bin}
. "$BIN_DIR/camera_common.sh"
WAIT_PID=

cleanup_demand() {
    [ -n "$WAIT_PID" ] && kill "$WAIT_PID" 2>/dev/null
    # 上一轮 MediaMTX 的迟到退出不能撤销新一轮的需求租约。
    if e11_generation_is_current; then
        e11_api_get '/api/stream-demand?on=false' >/dev/null
    fi
}
trap cleanup_demand EXIT
trap 'exit 0' INT TERM

while e11_generation_is_current; do
    # API 自身拒绝手动暂停及隐私模式；hook 不改 captureEnabled 或 privacy。
    e11_api_get '/api/stream-demand?on=true' >/dev/null
    sleep 10 &
    WAIT_PID=$!
    wait "$WAIT_PID"
    WAIT_PID=
done
