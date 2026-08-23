# 使用 Codex 自行完成本地构建

## 为什么采用自行构建

本仓库只提供项目自有的研究源码。以下内容不随仓库分发：

- APK、AAB、Magisk 成品包；
- Rockchip RKNN Runtime、Toolkit 和 C API 头文件；
- YOLO、YAMNet 或其他模型权重；
- MediaMTX、FFmpeg、厂商硬件工具、原厂 APK 和固件；
- 校准图片、家庭画面、事件短片、设备日志和通知密钥。

这些组件可能适用各自的许可证、设备授权或隐私义务。Codex 可以帮助使用者检查环境、解释官方文档、转换自己合法取得的模型和运行验证，但不能替使用者确认其是否拥有复制、部署或再分发权。

## 建议环境

- macOS 或 Linux 开发机；
- JDK 17；
- Android SDK 34、Build Tools 34；
- Android NDK 26.3 和 CMake 3.22；
- Android 11 / API 30 或更高的 arm64 目标设备；
- 如需模型转换，使用 Rockchip 明确支持的 Linux x86_64 环境。

不放入可选 RKNN 文件时，Android 源码基线仍可构建，NPU 会在运行时降级停用。哭声模型缺失时，摄像头、推流和动作检测仍可工作。

## 可直接交给 Codex 的任务说明

在本仓库目录打开 Codex，然后粘贴下面的说明。把方括号内容替换成你自己的本地路径，但不要粘贴通知密钥、家庭地址、设备序列号或私人画面：

```text
请先完整阅读 README.md、THIRD_PARTY_NOTICES.md、docs/NPU_ROADMAP.md、
docs/EVENT_TIMELINE.md 和本文件，再检查我的本机构建环境。

目标：在我拥有并获授权使用的 RK3566 Android 设备上自行构建和验证本项目。

边界：
1. 只使用我已经合法取得并放在本机的依赖；每个第三方组件先核对官方来源和许可证。
2. 不下载或使用来源不明的 APK、固件、模型、运行库和厂商工具。
3. 不把 .so、rknn_api.h、*.rknn、*.onnx、*.tflite、APK、校准图片、
   家庭画面、事件短片、设备 IP/序列号、Bark Key 或日志提交到 Git。
4. 模型转换和部署只在本机完成；先显示将要执行的路径和验证步骤。
5. 使用 JDK 17 运行单元测试、assembleDebug 和 lintDebug，随后在真机验证
   摄像头帧率、时间戳、NPU 降级/加载、哭声诊断和事件短片。
6. 最后做 git status --ignored、敏感信息和大文件审计；Git 提交信息使用中文，
   不生成 Release，也不 push，除非我另行明确要求。

我的本地依赖目录是：[仅填写本机目录，不要复制文件内容到对话]
我的目标设备连接方式是：[USB adb 或你自己的局域网方式]
```

Codex 应先给出环境审计结果，再执行构建。任何许可证不明确的依赖都应停在“待使用者确认”，不能因为技术上可以下载就默认允许分发。

## 可选 NPU 文件的本地位置

使用者从 Rockchip 官方渠道自行取得并接受相应条款后，可在本地放置：

```text
android-app/app/src/main/cpp/rknn_api.h
android-app/app/src/main/jniLibs/arm64-v8a/librknnrt.so
```

这两个路径已被 `.gitignore` 排除。CMake 只有在头文件和运行库同时存在时才编译 `libe11npu.so`，否则输出不含 NPU JNI 的普通版本。

person 模型由使用者在自己的 Linux 环境转换：

```bash
python scripts/convert_yolov8_rknn.py \
  --onnx /合法取得的本地路径/yolov8n.onnx \
  --dataset /已授权并清理隐私的本地路径/dataset.txt \
  --output /本地输出路径/yolov8n_rk3566.rknn
```

转换前需要同时核对模型仓库许可证、上游权重许可证和 RKNN Toolkit 条款。不要把家庭校准图或转换后的权重复制到本仓库。

应用运行时从自身私有目录读取：

```text
files/yolov8n_rk3566.rknn
files/yamnet.tflite
```

不同系统对 `run-as`、root 和应用数据目录的限制不同。让 Codex先通过只读命令检查设备，再选择适合该设备的本地部署方式；不要把某台设备的 UID、序列号或固定 IP 写入脚本和文档。

## 构建与验证基线

```bash
cd android-app
./gradlew testDebugUnitTest assembleDebug lintDebug
```

建议验证：

- 页面和 H.264/RTSP 画面中的东八区日期时间每秒更新；
- 整页刷新按钮在添加到 iOS 主屏幕后仍可用；
- 宝宝守护顶部快捷按钮可按当前状态开启或关闭，下面的完整设置入口仍可用；
- 镜头开放时动作、NPU、哭声和短片正常；镜头罩关闭时只运行哭声检测，NPU、动作和短片停止；
- 软件隐私模式仍同时停止摄像头和音频，不与实体镜头罩模式混淆；
- NPU 每 2 秒低频推理，不拖慢约 30fps 编码；
- 动作/疑似哭声事件前的 NPU 补跑在 300ms 上限内返回或清晰记录超时降级；
- NPU 不根据重点动作区域推断宝宝身份；
- 检测到两人或成人体型证据时，动作/哭声仍记录但不发送 Bark；
- 事件时间线可以内部滑动，原因、人数和通知结果正确；
- 60 秒事件会话只发一次通知、只创建一段短片，会话明细可展开；
- 底噪和成人站位校准只保存统计值，反馈标签不会自动改阈值；
- 24 小时健康页面能区分主动暂停与卡帧，并显示 NPU、音频、重启和存储变化；
- 测试短片可播放、支持逐条删除和一键清理历史，清理后解释记录仍保留；
- 缺少任一可选模型或 NPU 文件时，其他链路可以降级运行。

## 提交前审计

至少检查：

```bash
git status --short --ignored
git diff --check
git ls-files
```

同时搜索本机绝对路径、局域网 IP、设备序列号、通知密钥、证书、家庭成员姓名和媒体文件。只提交源码、测试、通用配置和不含私人现场数据的说明文档。

项目自有源码适用 MIT License；第三方组件和模型不因放在同一设备上而自动变成 MIT。不要把自行构建的 APK、模型、运行库或设备工具上传到项目 Release。
