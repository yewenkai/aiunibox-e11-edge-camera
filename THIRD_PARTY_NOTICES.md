# Third-party notices

本文件用于说明依赖关系，不替代各项目自身的许可证文本。

## 构建期和源码依赖

| 组件 | 用途 | 许可证 | 获取方式 |
| --- | --- | --- | --- |
| AndroidX / Jetpack Compose | Android UI、生命周期与平台兼容 | Apache License 2.0 | Gradle / Google Maven |
| NanoHTTPD | App 内嵌 HTTP 服务 | BSD 3-Clause | Gradle / Maven Central |
| Gradle Wrapper | Android 构建引导 | Apache License 2.0 | 仓库中的标准 wrapper |

## 运行期外部组件

| 组件 | 用途 | 许可证 | 是否随仓库分发 |
| --- | --- | --- | --- |
| MediaMTX | RTSP/WebRTC 媒体路由 | MIT | 否 |
| Android 系统及 Camera2/MediaCodec | 相机与硬编码平台接口 | 由设备和 Android 发行版决定 | 否 |
| `/vendor/bin/hw/ipcamera_test` | 特定样机的云台、IR-CUT 和补光接口 | 由设备厂商决定 | 否 |
| Rockchip RKNN 工具链 | 规划中的 NPU 推理 | 由 Rockchip 发布条款决定 | 否 |

仓库不包含 FFmpeg、商业 APK、反编译文件、厂商固件、私有库、模型权重或训练数据集。

本项目也不发布预编译 APK、Magisk 成品模块或打包后的第三方运行时。使用者必须从合法来源自行取得依赖，并自行审阅许可证和编译源码。

参考：

- AndroidX: https://github.com/androidx/androidx
- NanoHTTPD: https://github.com/NanoHttpd/nanohttpd
- MediaMTX: https://github.com/bluenviron/mediamtx
- RK3566: https://www.rock-chips.com/a/en/products/RK35_Series/2021/0113/1274.html
