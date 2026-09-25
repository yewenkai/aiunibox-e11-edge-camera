# 宝宝监控 · Android TV

独立电视接收端，包名 `org.e11camera.tv`，与摄像头端 APK 分开安装。
最低 Android 6.0，1.1.0 起内置原生 WebRTC SDK，直接使用 WHEP 协商及原生视频解码，不依赖系统 WebView。

## 使用

设备 WebRTC 地址示例为 `http://<device-ip>:8889/cam/`，请先在菜单中配置。
如设备地址变化，可在菜单中修改；输入控制页地址（`http://设备IP:8080`）会自动转换为
同设备的 `8889/cam/` WebRTC 入口。手动修改后的地址保存在电视本机并优先使用。

- 遥控器确认键、菜单键或返回键打开菜单，可重连、更换地址、切换完整画面/铺满和退出。
- 遥控器左右键点按控制云台左右转动，调用同设备 `8080/api/motor`，每次 83 步、速度 400，与网页方向一致。长按只执行一次，快速重复按键限流且不排队；底部显示执行结果。菜单或输入框中仍按系统规则导航。
- 默认完整画面；原生 WebRTC 当前仅接收视频。RTSP 模式的流内声音需视频源本身包含音轨，不等同于控制网页独立的麦克风监听通道。
- 播放时保持屏幕常亮，切到后台释放连接，返回前台重新连接。
- 每三秒检查帧到达时间，超过二十秒无帧重新建立 WebRTC 会话。
- 电视和设备须能互通 TCP 8889 及 MediaMTX 配置的 ICE 媒体端口（当前部署为 UDP 8189）。

原生 WebRTC 为默认播放方式；不会自动切换协议。菜单提供 Media3 RTSP 手动备用模式，
连接同设备的 `8554` 端口，使用 RTP-over-TCP，设置短缓冲。低延迟需以真机观察为准。
此版本支持局域网 HTTP WHEP 入口，观看不需要申请电视摄像头或麦克风权限。

## 构建与安装

在 `android-app/` 下使用 JDK 17 和 Android SDK 34：

```bash
./gradlew :tv:assembleDebug :tv:lintDebug
adb -s <电视地址>:5555 install -r tv/build/outputs/apk/debug/tv-debug.apk
adb -s <电视地址>:5555 shell am start -n org.e11camera.tv/.MainActivity
```

也可把该 APK 复制到 U 盘，通过电视文件管理器手动安装。
APK 为本地构建产物，Git 仅保存源码。
