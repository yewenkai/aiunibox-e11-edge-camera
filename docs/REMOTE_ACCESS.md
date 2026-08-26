# 远程网页与同源 HLS

## 目标

极空间一类“远程访问家中网页”通常只转发控制页所在的 HTTP 服务，浏览器无法继续直连
设备的 WebRTC 信令端口 `8889` 和媒体 UDP 端口 `8189`。页面和 `/api/status` 因而可以
正常显示，但 WebRTC 播放区域可能保持黑屏。

本项目不要求设备配置 HTTPS 或自签名证书。设备仍使用内网 HTTP：

```text
浏览器（极空间外层可以是 HTTP 或 HTTPS）
  └─ 当前控制页同源 /media/cam/...
       └─ Android NanoHTTPD 流式代理
            └─ http://127.0.0.1:8888/cam/...（设备本机 MediaMTX HLS）
```

浏览器看到的 HLS 请求始终与控制页同源，不会直接连接设备的 `8888` 端口。极空间只需
继续转发原有控制页入口；无需再映射 HLS、WebRTC 或 UDP 端口。
Android 应用明确允许访问设备本机的明文 HTTP 上游，否则 Android 9 及以上会在代理层
拦截 `127.0.0.1:8888`；该设置不负责把远程入口改成 HTTPS。

## 自动选择逻辑

- `http://局域网地址:8080` 直连：先探测 `8889`，确实可达时使用 WebRTC，延迟最低。
- 反向代理、远程域名或 HTTPS 页面：自动使用同源 HLS，外层协议不影响选择。
- 远程服务即使保留了内网形式的网址，只要 `8889` 探测失败，也会自动切换 HLS。
- HLS 清单连续三次不可用：自动降级为 `/snapshot` 每秒轮询，页面明确显示“快照兜底”。

排障时可给控制页添加查询参数强制指定通道：

```text
?stream=webrtc
?stream=hls
?stream=snapshot
```

HLS 使用普通 fMP4 分片，优先兼容多层 HTTP 代理；通常会比内网 WebRTC 多几秒延迟。
网页把“设备编码状态”和“浏览器播放通道”分开显示，设备 30fps 不再被误解为浏览器
已经成功播放。

## 双向语音的远程入口

双向语音不经过 MediaMTX，使用控制页同源的 `/intercom` WebSocket。极空间一类
远程代理需同时满足：

- 能够把 `/intercom` 的 `Connection: Upgrade` 和 `Upgrade: websocket` 透传到设备 `8080`。
- 外层页面是浏览器信任的 HTTPS，这样 iOS/Chrome 才会授予网页麦克风。

如果代理不支持 WebSocket Upgrade，视频 HLS 仍可以正常播放，只有双向语音显示
“通道连接失败”。如果直接用普通局域网 HTTP 打开，设备麦克风到浏览器的监听可用，
但浏览器麦克风上行会被安全上下文规则拒绝。应用不会因此在设备内启动自签名 HTTPS。

## 部署与检查

需要同时更新 APK、`mediamtx.yml` 和 `service.sh`。若设备沿用旧版 `ptz_rtsp` Magisk
模块，更新模块目录中的 `service.sh`；不要与 `service.d` 版本同时运行。设备端检查：

```bash
adb shell su -c "ss -lnt | grep -E ':8080|:8554|:8888|:8889'"
adb shell su -c "busybox wget -qO- http://127.0.0.1:8888/cam/index.m3u8 | head"
adb shell su -c "busybox wget -qO- http://127.0.0.1:8080/media/cam/index.m3u8 | head"
```

后两条都应以 `#EXTM3U` 开头。控制页显示“HLS · 远程兼容”后，再从极空间远程入口
确认画面、东八区画面内时间戳、全屏和画中画。

控制 API 的 Bearer Token 不覆盖 `/media/cam/`，这与原 MediaMTX WebRTC 播放端口的
认证边界一致。该方案面向用户自行管理的家庭网络和远程访问通道。
