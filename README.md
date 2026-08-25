# AIUniBOX-E11 Edge Camera

把一台闲置的 AIUniBOX-E11 RK3566 Android 带屏设备改造成局域网边缘摄像头：复用内置摄像头、水平云台和指示灯，在浏览器中通过 WebRTC 低延迟观看，也可通过同源 HLS 兼容远程网页代理，并通过 RTSP 接入其他家庭网络设备。

> 本项目仅用于个人设备研究、Android/音视频与边缘计算学习。它不是任何设备厂商、运营商或芯片公司的官方项目，也未获得这些主体的背书。请只在你拥有或已获明确授权的设备和场所中使用，并遵守当地隐私、监控和无线网络相关法律。

## 仅提供研究源码

本仓库不提供或分发任何可直接安装、刷入或运行的成品，包括但不限于：

- 预编译 APK / AAB
- Magisk 成品模块或一键安装包
- MediaMTX、FFmpeg、厂商工具等第三方可执行文件
- 原厂 APK、固件、驱动、私有库或模型权重

有研究需要的使用者应自行了解 Android/Gradle 项目的构建方式，审阅代码和依赖，配置合法取得的 Android SDK、JDK 与设备侧组件，并自行编译生成适用于其设备的版本。仓库中的部署配置和命令只用于说明研究过程，不构成开箱即用的安装支持。

不熟悉 Android/RKNN 工具链的使用者，可以按照[使用 Codex 自行完成本地构建](docs/SELF_BUILD_WITH_CODEX.md)在自己的电脑和设备上逐步检查、转换、编译和验收；指南明确要求 Codex 不把第三方二进制、模型、家庭数据或设备信息带入 Git。

GitHub Actions 仅验证源码是否可以编译，不上传 APK 或其他构建产物。

## 当前能力

- Camera2 采集，当前以 640×480、目标 30fps 运行
- MediaCodec Surface 硬编码，避免 CPU 侧 YUV 拷贝
- App 内直接发布 H.264/RTP 到 MediaMTX，不经过 FFmpeg
- GPU 零拷贝前置合成东八区日期时间，时间戳进入 H.264 画面，可用于全屏、画中画和录像
- 设备本机前置预览保持镜像；编码时只翻转摄像头纹理，网页、RTSP 和事件短片恢复自然视角，时间戳保持正向
- RTSP、内网 WebRTC 与远程同源 HLS 播放
- 内置局域网控制页，含独立的整页刷新按钮；云台按钮紧邻画面，向下浏览时直播自动缩为右上角悬浮窗
- App 前台 4:3 本地回显
- 水平云台点动与长按连续调整
- 红外灯、补光灯、IR-CUT 和 RGB 状态灯控制
- 有观看者时亮灯提示
- Bearer Token 保护设备 HTTP API
- Home Assistant / HACS 自定义集成
- 云台软件零点、常用位置与隐私模式
- 实体镜头罩模式：手动声明镜头罩已关闭后，宝宝守护仅保留麦克风哭声检测
- RK3566 NPU 低频通用人体检测（实验性上下文补强，不能识别年龄或宝宝身份）
- 可选 YOLOv8-Pose 睡姿影子模式：每 2 秒记录姿态、关键点有效率和耗时，默认不通知
- 浏览器可选调试骨架：默认关闭，只由网页 canvas 绘制，不进入直播、画中画或事件短片
- 风险候选时可选按需面部可见性分析；只分析头部裁剪区域，不做人脸识别或身份比对
- 宝宝守护：顶部快捷开关、可选重点区域动作检测、本地 YAMNet 哭声检测和 Bark 多设备通知
- 陪护状态判断：检出两人或成人体型证据时暂停通知；陪护消失后自动恢复并重新评估持续信号
- 可解释事件时间线：记录动作/哭声/可选姿态候选、人数、通知或抑制原因，列表在页面内部滑动
- 告警前 NPU 复核：事件触发时让常规轮询让路，最多等待 450ms 更新成人陪护证据
- 连续事件会话：固定 60 秒内的动作、疑似哭声和陪护变化合并为一条，只通知一次
- 端侧事件短片：复用 H.264 硬编码保存事件前约 10 秒、后约 20 秒，可播放、逐条删除或一键清理历史
- 场景校准向导：本地测量房间底噪和成人常见站位，只保存统计阈值
- 事件反馈：端侧标记“确实需要关注 / 误报 / 成人在场”或姿态“判断正确 / 错误”，不会自动改阈值
- 滚动 24 小时健康诊断：统计卡帧、编码帧率、NPU 耗时、音频更新、服务启动和存储变化
- Magisk/service.d 开机启动与健康守护示例

本项目目前是面向 AIUniBOX-E11 样机的实验性实现，不是通用摄像头应用。云台和灯光功能依赖设备已经存在的本地硬件控制接口。

## 架构

```text
内置摄像头
   │
   ├─ Camera2 ──> SurfaceView 本地回显
   │
   └─ SurfaceTexture ──> GLES 东八区时间戳 ──> MediaCodec H.264
                                                 ├─> App 内 RTSP Publisher
                                                 └─> 事件短片循环缓存 / MP4
                              │
                              v
                           MediaMTX
                         ┌────┴──────────────┐
                         │                   │
                       RTSP        WebRTC / HLS

浏览器控制页 ──> NanoHTTPD API ──> 云台 / LED / IR-CUT
       └──────> /media/cam 同源代理 ──> MediaMTX HLS
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

NPU、YAMNet 和 MediaMTX 都是可选的本地组件，仓库不提供对应模型或二进制。完整的源码自建边界、Codex 提示词和提交前审计见 [docs/SELF_BUILD_WITH_CODEX.md](docs/SELF_BUILD_WITH_CODEX.md)。

## 部署 MediaMTX

本仓库不分发 MediaMTX 可执行文件。请从 [MediaMTX 官方发布页](https://github.com/bluenviron/mediamtx/releases)自行取得与你设备架构匹配的版本，并遵守其 MIT License。

将可执行文件、`deploy/magisk/mediamtx.yml`、两个观看者 hook 和 `service.sh` 按 [deploy/magisk/README.md](deploy/magisk/README.md) 部署到设备。默认端口：

| 功能 | 地址 |
| --- | --- |
| 控制页 | `http://<device-ip>:8080` |
| RTSP | `rtsp://<device-ip>:8554/cam` |
| WebRTC | `http://<device-ip>:8889/cam/` |
| HLS（设备内网调试） | `http://<device-ip>:8888/cam/` |
| HLS（控制页同源） | `<控制页地址>/media/cam/` |

控制页在 `HTTP + 局域网地址 + 8080` 直连且 `8889` 探测可达时优先使用 WebRTC；通过极空间一类远程网页
代理访问时，无论外层是 HTTP 还是 HTTPS，都会自动切换到同源 HLS。设备不需要配置
自签名 HTTPS，远程入口也不需要单独转发 8888、8889 或 8189。部署与排障见
[远程网页与同源 HLS](docs/REMOTE_ACCESS.md)。

### 启用 API Token

应用启动时会读取 `/data/local/tmp/e11-edge-camera/api_token`。文件存在且非空时，
除首页、`/api/info` 和同源 HLS `/media/cam/` 外的 HTTP 接口都要求 Bearer Token：

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

HACS 版本从 v0.1.2 起使用 GitHub Release 中的 `aiunibox_e11.zip` 精简发布包。
该 ZIP 根目录只包含 `custom_components/aiunibox_e11` 内运行集成所需的文件，
不会把 Android 工程、Magisk 脚本或设备端文档下载到 Home Assistant。完整仓库仍
作为设备端主仓库保留；若未来申请进入 HACS 默认仓库，可再把已经隔离好的
`custom_components/aiunibox_e11` 迁移为独立仓库。

本地可运行 `scripts/build_hacs_release.sh /tmp/aiunibox_e11.zip` 检查发布边界。
推送与集成版本一致的 `v*` 标签后，GitHub Actions 会自动生成精简 ZIP 并创建
Release；标签版本与 `manifest.json` 不一致时会拒绝发布。

云台位置是软件累计值。首次部署时请把镜头转到希望作为中心的位置，再按一次
“当前位置设为零点”；如果曾绕过本应用直接执行厂商电机命令，需要重新设置软件零点。

## NPU 与轻量视觉

RK3566 官方标称约 1 TOPS NPU，适合低频率、轻量化的边缘视觉任务。当前已实现第一阶段实验链路：

1. 日常复用约每秒一张的 JPEG 快照，每 2 秒运行一次 RKNN；告警时普通轮询主动让路，并在 450ms 总预算内等待新帧和完成复核，不阻塞 Camera2 和编码线程。
2. 使用 Rockchip 优化版 YOLOv8n 的 9 路输出，只解码 COCO person 类；最近 3 帧至少 2 帧命中才进入“有人”，连续 10 秒未命中才退出。
3. 保留当前帧的全部人体框和人数供状态判断；人体框不烧进直播，也不持久化。
4. RKNN SDK、运行库和模型由研究者自行从合法来源取得，缺失时应用自动降级，不影响摄像头与推流。

在通用人体链路之外，页面可独立开启 YOLOv8-Pose 影子分析。它同样每 2 秒复用一张
JPEG，只把最近姿态、17 个 COCO 关键点的有效率和端到端耗时写入滚动 24 小时分钟统计。
多人画面会优先跟随与上一帧重叠的目标，短时漏检 3 次后才清空跟踪，减少骨架在成人和宝宝之间跳动。
稳定姿态要求最近 6 次中至少 5 次得到同一标签。该启发式只粗分“疑似仰卧 / 侧卧 /
俯卧 / 不明确”，不能确认宝宝身份，也不是医学结论。

调试骨架默认关闭，由浏览器在 WebRTC/HLS 画面上方的透明 canvas 临时绘制，因此不会修改
H.264、JPEG、画中画或事件短片。风险候选出现时还可按需加载 RetinaFace mobile320，
只分析由肩髋方向或可见面部关键点估计出的头部裁剪区域，并输出“面部可见 / 未确认可见”
附加证据；它不保存裁剪图、不识别人脸，也不持续运行。

姿态通知是独立的实验开关，默认关闭。即使手动开启，当前 Pose 或通用人体结果只要发现
两人、成人体型或仍处于陪护缓冲期，就硬性禁止外发；准备通知前还会限时补跑一次新画面
人体 NPU。成人离开且风险姿态仍持续时会重新评估，而不是被第一次压制永久吞掉。姿态记录和人工反馈仍保留在设备端。
建议先累计至少 24 小时影子样本，并在页面多次反馈“判断正确 / 错误”后再决定是否启用。

正常守护采用安全侧策略：当前帧一旦出现两人或成人体型证据，就即时抑制当次 Bark；最近 4 次中至少 3 次出现这些证据后，页面稳定确认为“有人陪护”。成人体型门槛会按人体框在画面中的远近做有限透视补偿，但仍只是固定机位启发式，不是年龄识别。陪护证据消失约 20 秒后自动恢复通知；若此前被成人压制的哭声或动作仍有新证据，会立刻重新评估当前事件会话，不等待新的上升沿。

实体镜头罩关闭时，网页需要同步选择“镜头罩关闭”。此时 Camera2、编码和直播基础链路仍保持运行，但宝宝守护会停止 NPU、动作检测和事件短片缓存，只运行 YAMNet 哭声检测。因为系统无法从遮挡画面确认成人是否在场，疑似哭声达到阈值时会按安全侧通知，并在事件原因中标明“仅音频、未分析画面”。这与软件隐私模式不同：软件隐私模式会同时停止摄像头和麦克风守护。

哭声检测使用 16kHz 单声道、约 0.975 秒窗口、约 0.49 秒步进的本地 YAMNet，直接读取模型输出的 0～1 类别分数并取哭声相关类别最高分。强候选立即成立，较弱候选需要最近 3 窗中至少 2 窗命中；安静窗口也会周期性抽查，避免远处弱哭声被能量门限永久跳过。网页显示实时音频 RMS、哭声分数、阈值和最高类别；现场向导采集 30 个重叠统计窗口，仍约等于 15 秒，校准期间不触发哭声通知。

动作检测同时比较相邻约 1 秒的短帧差和约 5 秒累计帧差：强动作可经“3 次中 2 次”快速通道触发，普通动作仍需“15 次中 8 次”的持续证据。全局亮度先做均值归一化，云台、IR-CUT 或补光灯变化会清空参考帧；用户框选区域仍只影响动作链路。

宝宝不必位于婴儿床或固定位置。用户框选的重点区域只影响动作检测，不参与成人陪护或宝宝身份判断。视觉仍只是“补强信号”：通用 COCO person 模型在侧卧、遮挡、逆光等场景下可能漏检，因此页面只陈述“检出人体 / 未检出人体”，不能把后者解释为确定无人。

动作或哭声事件会写入可解释时间线；正常守护还会在设备应用私有目录保存带画面时间戳的短片，仅音频守护不生成画面短片。记录上限为 200 条；短片上限为 60 段或 256 MiB，达到上限时淘汰最旧短片，用户也可逐条删除或一键清理全部历史短片。清理视频不会删除事件解释。当前短片不含声音。处理顺序、校准、反馈和健康诊断见 [docs/GUARDIAN_LOGIC.md](docs/GUARDIAN_LOGIC.md)，时间线与短片边界见 [docs/EVENT_TIMELINE.md](docs/EVENT_TIMELINE.md)，NPU 转换见 [docs/NPU_ROADMAP.md](docs/NPU_ROADMAP.md)。

设备 BLE 可作为 Home Assistant 远程适配器的候选，但三菱控制要求主动 GATT 读写和 Notify，不是简单广播扫描。本仓库不捆绑第三方代理；独立应用的验证边界和回退方案见[可选 BLE Proxy 指南](docs/BLE_PROXY.md)。

## 仓库结构

```text
.
├── android-app/           Android Camera2 / MediaCodec / 控制页源码
├── custom_components/     Home Assistant 自定义集成
├── brand/                 HACS 展示图标
├── deploy/magisk/         MediaMTX 最小配置与开机守护示例
├── docs/                  移植、NPU、事件短片与源码自建说明
├── LICENSE                本项目 MIT License
├── SECURITY.md            安全与隐私边界
└── THIRD_PARTY_NOTICES.md 第三方依赖与未分发组件说明
```

## 开源与版权说明

公开目录经过以下处理：

- 不包含原厂 APK、反编译产物、固件、私有库或设备抓取文件
- 不包含 MediaMTX、FFmpeg 等第三方可执行文件
- 不包含 HTTPS 私钥、自签名证书、家庭画面、设备序列号、MAC 或局域网固定地址
- 不包含 Bark Key、事件数据库、哭声音频、端侧短片或真实校准图片
- Android 包名和应用名称均使用项目自己的中性标识
- 第三方依赖由 Gradle 或用户从官方渠道取得，并在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中说明

产品名、公司名和商标仅在描述兼容性或来源时使用，权利归其各自所有者。

## License

项目自有源码采用 [MIT License](LICENSE)。第三方组件仍适用各自许可证。
