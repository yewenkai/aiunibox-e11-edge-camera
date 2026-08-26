# 局域网双向语音

## 使用方式

控制页直播画面下方有两个独立操作：

- **监听设备**：把设备麦克风的实时声音播放到当前浏览器。多个页面可同时监听。
- **按住说话**：按下后采集浏览器麦克风，由设备 `AudioTrack` 实时播放；松开即结束。同一时刻只有一个页面可取得说话权。

默认使用半双工：页面按住说话时，设备麦克风回传会暂停。设备上的哭声分析同时暂停，并在播放结束后留出 1.5 秒冷却时间，避免把扬声器中的家人语音当成哭声。

## 数据链路

```text
设备麦克风
  └─ MicrophoneAudioSource（16kHz / mono / PCM16）
       ├─ CryDetector 的 YAMNet 窗口组装
       └─ /intercom WebSocket 二进制帧 ─> 浏览器 Web Audio

浏览器 getUserMedia
  └─ 重采样为 16kHz / PCM16LE
       └─ /intercom WebSocket 二进制帧 ─> AudioTrack ─> 设备扬声器
```

哭声检测与网页监听不再各自打开 `AudioRecord`，从而避免音频 HAL 独占冲突。麦克风数据只在内存中转发，此功能不生成音频文件。

## WebSocket 协议

- 地址：与控制页同源的 `/intercom`，HTTP 页面使用 `ws://`，HTTPS 页面使用 `wss://`。
- 鉴权：启用 API Token 后，握手查询参数使用 `token`。
- 音频：二进制帧均为 16,000Hz、单声道、有符号 PCM16 小端序。网页上行以 320 样本（20ms）分帧。
- 控制消息：`{"type":"listen","active":true}` 和 `{"type":"talk","active":true}`；结束时将 `active` 设为 `false`。
- 保活：页面每 3 秒发送一次 `{"type":"ping"}`，避免 NanoHTTPD 的空闲读超时中断长时监听。
- 服务器会回传 `status`、`talk-granted` 或带有稳定 `code` 的 `error` JSON。

## HTTP 与 iOS 限制

设备本身仍然可以纯 HTTP 运行，没有内置自签名 HTTPS：

- 设备麦克风到浏览器的“监听设备”不依赖 `getUserMedia`，纯 HTTP 页面可用。
- 浏览器到设备的“按住说话”需要 `getUserMedia`。iOS 和 Chrome 只在可信上下文中授予网页麦克风，普通局域网 HTTP 页面会被浏览器拒绝。
- 从极空间等可信 HTTPS 远程入口打开时，反向代理还必须支持 `/intercom` 的 WebSocket Upgrade。不需要给 Android 应用自己加 HTTPS。

页面会先检查可信上下文；不满足时仍保留监听按钮，并明确说明为什么无法按住说话。

## 隐私模式和镜头罩

- 软件隐私模式会禁止设备麦克风回传，但仍允许家人从网页向设备扬声器说话。
- 实体镜头罩只遮挡画面；符合项目的“仅音频守护”定义，双向语音仍可使用。

## 无设备时可完成的验证

- JVM 测试：PCM 任意分块与 50% 重叠窗口不丢样；多监听者与单说话者会话规则。
- Android 构建：`AudioRecord`、`AudioTrack`、NanoWSD 和前端资源全部通过编译与打包。
- 浏览器测试：按钮布局、HTTP 限制提示、按住/松开状态与页面滚动。

真机到手后仍需要确认扬声器音量、底噪、延迟、极空间 WebSocket 透传，以及不同浏览器的回声表现。只有这些完成后，才能声明双向语音已在目标设备上验收。
