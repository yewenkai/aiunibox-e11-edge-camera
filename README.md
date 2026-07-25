# AIUniBOX-E11 Edge Camera

把一台闲置的 AIUniBOX-E11 RK3566 Android 带屏设备改造成局域网边缘摄像头：复用内置摄像头、水平云台和指示灯，在浏览器中通过 WebRTC 低延迟观看，并通过 RTSP 接入其他家庭网络设备。

> 本项目仅用于个人设备研究、Android/音视频与边缘计算学习。它不是任何设备厂商、运营商或芯片公司的官方项目，也未获得这些主体的背书。请只在你拥有或已获明确授权的设备和场所中使用，并遵守当地隐私、监控和无线网络相关法律。

## 仅提供研究源码

本仓库不提供或分发任何可直接安装、刷入或运行的成品，包括但不限于：

- 预编译 APK / AAB
- Magisk 成品模块或一键安装包
- MediaMTX、FFmpeg、厂商工具等第三方可执行文件
- 原厂 APK、固件、驱动、私有库或模型权重

有研究需要的使用者应自行了解 Android/Gradle 项目的构建方式，审阅代码和依赖，配置合法取得的 Android SDK、JDK 与设备侧组件，并自行编译生成适用于其设备的版本。仓库中的部署配置和命令只用于说明研究过程，不构成开箱即用的安装支持。

GitHub Actions 仅验证源码是否可以编译，不上传 APK 或其他构建产物。

## 当前能力

- Camera2 采集，当前以 640×480、目标 30fps 运行
- MediaCodec Surface 硬编码，避免 CPU 侧 YUV 拷贝
- App 内直接发布 H.264/RTP 到 MediaMTX，不经过 FFmpeg
- RTSP 与 WebRTC 播放
- 内置局域网控制页
- App 前台 4:3 本地回显
- 水平云台点动与长按连续调整
- 红外灯、补光灯、IR-CUT 和 RGB 状态灯控制
- 有观看者时亮灯提示
- Bearer Token 保护设备 HTTP API
- Home Assistant / HACS 自定义集成
- 云台软件零点、常用位置与隐私模式
- Magisk/service.d 开机启动与健康守护示例

本项目目前是面向 AIUniBOX-E11 样机的实验性实现，不是通用摄像头应用。云台和灯光功能依赖设备已经存在的本地硬件控制接口。

## 架构

```text
内置摄像头
   │
   ├─ Camera2 ──> SurfaceView 本地回显
   │
   └─ MediaCodec H.264 ──> App 内 RTSP Publisher
                              │
                              v
                           MediaMTX
                         ┌────┴────┐
                         │         │
                       RTSP      WebRTC

浏览器控制页 ──> NanoHTTPD API ──> 云台 / LED / IR-CUT
```

## 已验证硬件

测试设备通过系统属性报告为 `AIUniBOX-E11`，属于 RK3566 平台的 Android 带屏终端。项目名称使用该型号是为了准确描述兼容对象，不代表与设备制造商、销售方或运营商存在合作、授权或背书关系。

| 项目 | 已验证信息 |
| --- | --- |
| SoC | Rockchip RK3566（设备平台字符串为 `rk356x`） |
| CPU | 四核 Arm Cortex-A55 |
| GPU | Mali-G52 |
| NPU | 官方规格约 1 TOPS |
| 多媒体 | 官方规格支持 1080p60 H.264/H.265 编码、8M ISP |
| 系统 | Android 11 / API 30 / arm64-v8a |
| 摄像头 | Android Camera2 可访问的内置前置摄像头；项目当前使用 640×480 |
| 云台 | TMI8150B 水平步进驱动；实测约 2986 步/圈、速度范围 30～854 步/秒 |
| 灯光 | `/sys/class/leds` 下的 RGB、红外、补光和工作指示灯节点 |
| 权限 | 摄像头权限；云台和部分灯光控制需要 root/Magisk |

RK3566 的 CPU、GPU、NPU 和多媒体能力以[瑞芯微官方 RK3566 规格页](https://www.rock-chips.com/a/en/products/RK35_Series/2021/0113/1274.html)为准。表中设备接口、步数和速度是对当前样机的观察值，其他固件或硬件批次可能不同。

### 硬件控制边界

- 项目会调用设备本地已有的 `/vendor/bin/hw/ipcamera_test`，但**不包含、不复制也不分发**该厂商程序。
- 项目只记录为实现兼容性所必需的调用方式；厂商程序、固件、商标和相关权利仍归各自权利人所有。
- 硬件复位参数 `-R` 在测试设备上可能让电机持续励磁，因此网页和 API 已禁用硬件复位。
- 日常电机动作使用 `-n` 同步模式，否则很短的点动也可能让工具进程停留约 5 秒，造成后续控制被误判为 busy。
- 电机动作进入最多 4 项的单线程有界队列：快速连点不会直接丢失，异常高频请求也不会无限累积。
- 如果你的设备没有相同接口，请实现自己的硬件适配层，不要复制来源不明的厂商 APK、库或固件到仓库。

## 自行构建 Android 应用

以下信息仅说明当前源码的构建基线。使用者需要自行具备 Android 开发和设备调试能力：

- JDK 17
- Android SDK 34
- Android 设备 API 30 或更高

```bash
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant org.e11camera.edge android.permission.CAMERA
```

首次执行硬件控制时，需要在 Magisk 中授予应用 root 权限。若设备中已有其他进程独占摄像头，应先通过设备自身设置安全地停止该进程；本仓库不提供针对特定商业应用的修改或破解文件。

## 部署 MediaMTX

本仓库不分发 MediaMTX 可执行文件。请从 [MediaMTX 官方发布页](https://github.com/bluenviron/mediamtx/releases)自行取得与你设备架构匹配的版本，并遵守其 MIT License。

将可执行文件、`deploy/magisk/mediamtx.yml`、两个观看者 hook 和 `service.sh` 按 [deploy/magisk/README.md](deploy/magisk/README.md) 部署到设备。默认端口：

| 功能 | 地址 |
| --- | --- |
| 控制页 | `http://<device-ip>:8080` |
| RTSP | `rtsp://<device-ip>:8554/cam` |
| WebRTC | `http://<device-ip>:8889/cam/` |

### 启用 API Token

应用启动时会读取 `/data/local/tmp/e11-edge-camera/api_token`。文件存在且非空时，
除首页和 `/api/info` 外的 HTTP 接口都要求 Bearer Token：

```bash
adb shell "su -c 'mkdir -p /data/local/tmp/e11-edge-camera && chmod 700 /data/local/tmp/e11-edge-camera'"
adb shell "su -c 'printf %s your-random-token > /data/local/tmp/e11-edge-camera/api_token && chmod 600 /data/local/tmp/e11-edge-camera/api_token'"
```

浏览器控制页会在首次控制时提示输入 Token，并只保存到当前浏览器会话。MediaMTX
的 RTSP、HLS 和 WebRTC 鉴权需要单独配置；因此即使启用了 API Token，也不要把
8080、8554、8888、8889 直接映射到公网，详见 [SECURITY.md](SECURITY.md)。

## Home Assistant 接入

本仓库包含 `custom_components/aiunibox_e11`，可通过 HACS 自定义仓库安装：

1. 在 HACS 中添加 `https://github.com/yewenkai/aiunibox-e11-edge-camera`，类型选择“集成”。
2. 下载 `AIUniBOX-E11 Edge Camera` 并重启 Home Assistant。
3. 在“设置 → 设备与服务 → 添加集成”中搜索 `AIUniBOX-E11`。
4. 输入设备 IP、HTTP/RTSP 端口和设备 API Token。

集成会创建摄像头、云台按钮、补光灯、RGB 状态灯、夜视场景、IR-CUT、隐私模式、
视频流状态、正在观看状态、软件角度和运行时间等实体。

云台位置是软件累计值。首次部署时请把镜头转到希望作为中心的位置，再按一次
“当前位置设为零点”；如果曾绕过本应用直接执行厂商电机命令，需要重新设置软件零点。

## NPU 与轻量视觉路线

RK3566 官方标称约 1 TOPS NPU，适合低频率、轻量化的边缘视觉任务。项目后续优先考虑：

1. 画面中是否有人：低分辨率、低帧率抽帧，输出“有人/无人”和置信度，不默认保存人脸。
2. 简单手势识别：先做手掌检测，再做少量静态手势分类，用于本地交互。
3. 事件触发：仅在有人或手势出现时通知、亮灯或提高分析频率。
4. 隐私优先：模型在设备本地运行，默认不上传原始画面。

NPU 推理尚未实现。Rockchip RKNN 工具链和模型文件不会直接提交到本仓库；引入前需要分别确认 SDK、转换工具、模型权重和数据集的许可证。详细设计见 [docs/NPU_ROADMAP.md](docs/NPU_ROADMAP.md)。

## 仓库结构

```text
.
├── android-app/           Android Camera2 / MediaCodec / 控制页源码
├── custom_components/     Home Assistant 自定义集成
├── brand/                 HACS 展示图标
├── deploy/magisk/         MediaMTX 最小配置与开机守护示例
├── docs/                  移植和 NPU 路线文档
├── LICENSE                本项目 MIT License
├── SECURITY.md            安全与隐私边界
└── THIRD_PARTY_NOTICES.md 第三方依赖与未分发组件说明
```

## 开源与版权说明

公开目录经过以下处理：

- 不包含原厂 APK、反编译产物、固件、私有库或设备抓取文件
- 不包含 MediaMTX、FFmpeg 等第三方可执行文件
- 不包含 HTTPS 私钥、自签名证书、家庭画面、设备序列号、MAC 或局域网固定地址
- Android 包名和应用名称均使用项目自己的中性标识
- 第三方依赖由 Gradle 或用户从官方渠道取得，并在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中说明

产品名、公司名和商标仅在描述兼容性或来源时使用，权利归其各自所有者。

## License

项目自有源码采用 [MIT License](LICENSE)。第三方组件仍适用各自许可证。
