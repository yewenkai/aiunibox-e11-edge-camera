# MediaMTX 与开机守护部署

本目录只包含供研究参考的配置和脚本，不包含 MediaMTX 可执行文件，也不是可直接刷入的完整 Magisk ZIP。本项目不会提供打包后的部署版本；使用者需要理解 Magisk/service.d、ADB 和 Android 权限模型后自行审阅与部署。

## 文件位置

把以下文件放到设备：

```text
/data/local/tmp/bin/mediamtx
/data/local/tmp/bin/mediamtx.yml
/data/local/tmp/bin/hook_on.sh
/data/local/tmp/bin/hook_off.sh
/data/local/tmp/bin/camera_common.sh
/data/local/tmp/bin/stream_demand.sh
/data/adb/service.d/aiunibox-e11-edge-camera.sh
```

其中 `aiunibox-e11-edge-camera.sh` 使用本目录的 `service.sh`。
如果设备已经安装旧版 `ptz_rtsp` Magisk 模块，则应更新
`/data/adb/modules/ptz_rtsp/service.sh`，不要再同时安装一份 `service.d` 脚本，否则会
出现两个守护进程。两种放置方式只选一种。

示例命令：

```bash
adb push /path/to/mediamtx /data/local/tmp/mediamtx.upload
adb push mediamtx.yml hook_on.sh hook_off.sh camera_common.sh stream_demand.sh service.sh /data/local/tmp/

adb shell su -c '
  mkdir -p /data/local/tmp/bin /data/adb/service.d
  cp /data/local/tmp/mediamtx.upload /data/local/tmp/bin/mediamtx
  cp /data/local/tmp/mediamtx.yml /data/local/tmp/bin/mediamtx.yml
  cp /data/local/tmp/hook_on.sh /data/local/tmp/bin/hook_on.sh
  cp /data/local/tmp/hook_off.sh /data/local/tmp/bin/hook_off.sh
  cp /data/local/tmp/camera_common.sh /data/local/tmp/bin/camera_common.sh
  cp /data/local/tmp/stream_demand.sh /data/local/tmp/bin/stream_demand.sh
  cp /data/local/tmp/service.sh /data/adb/service.d/aiunibox-e11-edge-camera.sh
  chmod 0755 /data/local/tmp/bin/mediamtx
  chmod 0755 /data/local/tmp/bin/hook_on.sh /data/local/tmp/bin/hook_off.sh
  chmod 0755 /data/local/tmp/bin/stream_demand.sh
  chmod 0644 /data/local/tmp/bin/camera_common.sh
  chmod 0755 /data/adb/service.d/aiunibox-e11-edge-camera.sh
'
```

安装 APK、授予摄像头权限和 Magisk root 权限后重启设备。已存在 Magisk 模块的设备，
更新后的模块 `service.sh` 也会在下次启动时生效。

`service.sh` 会：

1. 等待 Android 和 Wi-Fi 就绪
2. 使用 boot ID、PID 和进程命令行三重校验守护实例，避免重启后 PID 复用导致误判
3. 清理旧版 PID 文件和孤儿 MediaMTX 进程
4. 先启动并守护 MediaMTX，再启动应用 Activity
5. 在每次 MediaMTX 启动时清理读者状态，并用启动序号拒绝旧进程迟到的 hook
6. 每 10 秒续期观看状态，检查应用进程、MediaMTX 端口和 `/api/runtime-health`
7. 连续失败时重启异常服务

当前配置按设备上的 **MediaMTX v1.9.0** 官方配置核对。`cam` 的 `runOnDemand`
在无发布者的读者请求到来时启动 `stream_demand.sh`；脚本每 10 秒调用本机
`/api/stream-demand?on=true` 续期 35 秒的需求租约，并在退出时撤销。应用处于
手动暂停或隐私模式时拒绝唤醒。首帧等待上限为 15 秒；最后一个读者退出后
10 秒结束 demand hook。HLS muxer 在 15 秒无分段请求后释放读者，避免网页
关闭后继续维持一分钟的采集。字段依据见
[v1.9.0 官方配置](https://github.com/bluenviron/mediamtx/blob/v1.9.0/mediamtx.yml)。

观看标记改为 `/data/user/0/org.e11camera.edge/files/viewer-state/watchers`，格式
是 `0|1 elapsedRealtimeMs`，时间来自 `/proc/uptime`，精度为一秒。目录和文件
由应用以 `0700`、`0600` 创建，root hook 仅原位写已有 inode，不创建应用目录，
不更改文件 owner 或 SELinux 标签。应用使用 FileObserver 接收变化并每 15 秒
直接读取兜底，超过 35 秒的标记自动失效。`/sdcard/e11_edge_camera_watching`
不再参与观看状态判断。APK 和所有脚本需要一起更新。

内部 API 使用 `Authorization: Bearer`，token 从
`/data/local/tmp/e11-edge-camera/api_token` 读取，不写入 URL 或日志。优先使用
Magisk BusyBox 的 `wget`（带 3 秒超时），兼容支持 header 的系统 BusyBox、curl
和 toybox wget。读者变更使用 BusyBox `flock` 串行处理。缺少必要工具时启动脚本
会报告原因并停止，避免错误地循环重启应用。

守护状态保存在 `/data/local/tmp/e11-edge-camera/watchdog.state`，内容同时记录本次
boot ID 和 PID。旧版 `/data/local/tmp/e11_camera_watchdog.pid` 会在启动时自动清理。
端口检查使用 Android 的 `ss` 监听表，不依赖不同固件中参数支持不一致的 `nc`。

如需回滚到旧应用：

```bash
adb shell su -c 'am force-stop org.e11camera.edge; pm enable --user 0 com.unicom.ptzmonitor'
adb shell am start -n com.unicom.ptzmonitor/.MainActivity
```

再次启用本项目时，先关闭占用摄像头或 8080 端口的旧应用，再执行 `service.sh`；
脚本会恢复 `org.e11camera.edge`。

不同 MediaMTX 版本的配置字段可能变化。若启动失败，请以所下载版本自带的官方配置为准，保留本文件的 RTSP、HLS、WebRTC 端口和 `cam` path hook 即可。远程网页只代理 8080 时的同源 HLS 原理与验收步骤见 [`docs/REMOTE_ACCESS.md`](../../docs/REMOTE_ACCESS.md)。
