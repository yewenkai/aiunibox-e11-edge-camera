# 可选：将 E11 作为 Home Assistant 主动 BLE Proxy

## 结论

这项扩展在技术上可行，但不应直接把第三方代理源码或 APK 合入本仓库。建议把 BLE Proxy 作为独立 Android 应用运行，与摄像头应用解耦；先做现场验证，确认不会影响蓝牙遥控器，再决定是否停用原 ESP32-S3。

三菱空调控制不是只转发广播：Home Assistant 需要经代理主动建立 GATT 连接，完成服务发现、特征读取、分段写入和 Notify 响应。因此“只能扫描广播”的 Android BLE 工具不能替代现有 ESPHome `bluetooth_proxy.active: true`。

开源项目 [Home Assistant Mobile BLE Proxy](https://github.com/Zen3515/homeassistant-mobile-ble-proxy) 声明实现了 ESPHome Native API、mDNS、BLE 广播转发，以及 GATT connect/discover/read/write/descriptor/notification，功能契约与本场景相符。它是独立的 GPL-3.0 项目，不属于本仓库，也未经本项目在 AIUniBOX-E11 上完成 24 小时稳定性验证。

## 推荐验证方式

让 Codex 在独立目录中完成以下步骤，不要把第三方源码、APK、密钥、设备地址或家庭日志复制进本仓库：

1. 阅读第三方项目的 README、许可证、构建脚本和权限清单，从源码自行构建。
2. 以独立包安装到 E11，授权蓝牙、附近设备、定位和通知，并允许前台服务及开机启动。
3. 保持 ESP32-S3 在线作为回退路径，在 Home Assistant 中通过 ESPHome 集成添加 Android 代理。
4. 将三菱设备的精确地址加入代理的 Managed Target Devices，并启用锁屏扫描；避免无目标的长期宽泛扫描。
5. 在 Home Assistant 的“设置 → 蓝牙”中确认代理具备连接能力，并用连接监视器确认三菱集成实际通过 E11 建立 GATT 会话。
6. 验证状态读取、温度或模式下发、连续重连、E11 重启后恢复，以及屏幕关闭后的运行。
7. 同时反复操作 E11 蓝牙遥控器；如出现延迟、丢键、断连或扫描不稳定，停止该代理并继续使用 ESP32-S3。

## 验收门槛

- Home Assistant 显示 E11 代理在线且具备主动连接能力；
- 三菱集成可完成读、写、Notify 闭环，而不只是发现设备；
- 屏幕关闭和设备重启后仍能恢复；
- 蓝牙遥控器不受影响；
- 连续运行 24 小时无明显断连或异常耗电。

在这些条件全部满足前，不应称为 ESP32-S3 的等价替换。Home Assistant 的代理与连接概念见[官方 Bluetooth 文档](https://www.home-assistant.io/integrations/bluetooth/)，ESPHome 主动代理行为见[官方 Bluetooth Proxy 文档](https://esphome.io/components/bluetooth_proxy/)。
