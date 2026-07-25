# 硬件移植说明

当前实现针对一台 RK3566 Android 11 样机。移植到其他设备时，应优先替换硬件适配层，不要依赖商业 APK 或复制厂商私有库。

## 摄像头

`CameraStreamer.kt` 使用 Camera2 并优先选择前置摄像头。需要确认：

- Camera ID 与镜头朝向
- Camera HAL 支持的输出尺寸和帧率
- MediaCodec 是否接受 Surface 输入
- 同时输出编码 Surface、预览 Surface 和 ImageReader 时的流组合限制

## 云台

`MotorController.kt` 当前通过样机已有的 `/vendor/bin/hw/ipcamera_test` 调用水平步进电机。

移植时建议把以下能力封装为接口：

- `moveLeft(steps, speed)`
- `moveRight(steps, speed)`
- `isBusy()`
- 可选的限位或位置读取

网页中的“左/右”按观看画面移动方向定义。更换电机或镜头安装方向后，必须通过真实画面对照重新校准。

不要直接启用硬件复位。只有确认归零传感器、动作时序和释放励磁行为后，才能实现安全的居中功能。

## LED 与 IR-CUT

`LedController.kt` 使用 `/sys/class/leds` 和样机本地工具。不同设备的节点名、极性和亮度范围可能不同，必须使用白名单映射，禁止把 API 参数直接拼接到 root shell。

## Root 权限

当前原型通过 Magisk `su` 执行硬件命令。面向长期使用时，更合适的方案是：

- 使用权限收敛的本地守护进程
- 只暴露固定参数的 IPC 接口
- 不让 Web 请求直接决定 shell 命令内容
- 记录硬件动作审计日志
