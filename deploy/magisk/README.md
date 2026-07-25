# MediaMTX 与开机守护部署

本目录只包含供研究参考的配置和脚本，不包含 MediaMTX 可执行文件，也不是可直接刷入的完整 Magisk ZIP。本项目不会提供打包后的部署版本；使用者需要理解 Magisk/service.d、ADB 和 Android 权限模型后自行审阅与部署。

## 文件位置

把以下文件放到设备：

```text
/data/local/tmp/bin/mediamtx
/data/local/tmp/bin/mediamtx.yml
/data/local/tmp/bin/hook_on.sh
/data/local/tmp/bin/hook_off.sh
/data/adb/service.d/aiunibox-e11-edge-camera.sh
```

其中 `aiunibox-e11-edge-camera.sh` 使用本目录的 `service.sh`。

示例命令：

```bash
adb push /path/to/mediamtx /data/local/tmp/mediamtx.upload
adb push mediamtx.yml hook_on.sh hook_off.sh service.sh /data/local/tmp/

adb shell su -c '
  mkdir -p /data/local/tmp/bin /data/adb/service.d
  cp /data/local/tmp/mediamtx.upload /data/local/tmp/bin/mediamtx
  cp /data/local/tmp/mediamtx.yml /data/local/tmp/bin/mediamtx.yml
  cp /data/local/tmp/hook_on.sh /data/local/tmp/bin/hook_on.sh
  cp /data/local/tmp/hook_off.sh /data/local/tmp/bin/hook_off.sh
  cp /data/local/tmp/service.sh /data/adb/service.d/aiunibox-e11-edge-camera.sh
  chmod 0755 /data/local/tmp/bin/mediamtx
  chmod 0755 /data/local/tmp/bin/hook_on.sh /data/local/tmp/bin/hook_off.sh
  chmod 0755 /data/adb/service.d/aiunibox-e11-edge-camera.sh
'
```

安装 APK、授予摄像头权限和 Magisk root 权限后重启设备。

`service.sh` 会：

1. 等待 Android 和 Wi-Fi 就绪
2. 启动应用 Activity，让前台服务获得合法生命周期
3. 单实例守护 MediaMTX
4. 每 10 秒检查摄像头编码和 RTSP 发布状态
5. 连续失败时重启应用

不同 MediaMTX 版本的配置字段可能变化。若启动失败，请以所下载版本自带的官方配置为准，保留本文件的端口、WebRTC 和 `cam` path hook 即可。
