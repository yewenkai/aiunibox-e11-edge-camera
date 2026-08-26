# 局域网自建 HTTPS

## 用途与端口

iOS 和 Chrome 只允许可信页面调用浏览器麦克风。应用因此支持两个并行入口：

| 入口 | 用途 |
| --- | --- |
| `http://设备IP:8080/` | 保留兼容；视频、控制、设备麦克风监听 |
| `https://设备IP:8443/` | 可信后支持“按住说话”和同源 `wss://.../intercom` |

HTTPS 不替换 HTTP，也不改变设备内部 `127.0.0.1:8888` 的 MediaMTX HLS。浏览器通过
`8443` 请求 `/media/cam/`，由 Android 应用在设备内部完成代理。

## 生成并部署

先安装最新 Debug APK 并确认 ADB 已连接，然后在仓库根目录运行：

```bash
./scripts/setup_local_https.sh
```

脚本自动读取 `wlan0` IPv4；也可以显式指定：

```bash
./scripts/setup_local_https.sh <device-ip>
```

脚本执行以下操作：

1. 首次运行生成本地根 CA；后续复用同一个根 CA。
2. 为当前设备 IP 生成有效期 397 天、含 IP SAN 的服务器证书。
3. 只把服务器 PKCS#12、密码和公开根证书复制到应用私有目录。
4. 重启应用，使 `8443 HTTPS/WSS` 生效。

根 CA 私钥只保存在 `device-data/https/local-ca.key`，整个 `device-data/` 已被 Git 忽略。
APK、源码和设备都不包含根 CA 私钥。

## iPhone 安装信任

1. 使用 Safari 打开 `http://设备IP:8080/local-ca.crt` 并允许下载描述文件。
2. 在“设置 → 通用 → VPN 与设备管理”中安装 `AIUniBOX-E11 Local Root CA`。
3. 在“设置 → 通用 → 关于本机 → 证书信任设置”中为该根证书开启“完全信任”。
4. 打开 `https://设备IP:8443/`，首次使用“按住说话”时允许麦克风。

仅下载证书而没有完成第 2、3 步，Safari 仍会把页面视为不可信。不要从 HTTP 页面测试
“按住说话”，因为浏览器会在音频发送前直接拒绝 `getUserMedia`。

## 验证

Mac 上可使用脚本生成的根证书验证证书链、IP SAN 和应用状态：

```bash
curl --cacert device-data/https/local-ca.crt \
  https://设备IP:8443/api/info

curl --cacert device-data/https/local-ca.crt \
  https://设备IP:8443/media/cam/index.m3u8
```

`/api/info` 中应显示 `httpsConfigured=true`、`httpsRunning=true` 和 `appVersion=1.8.0`。

## IP 变化与续期

证书必须匹配浏览器地址栏中的 IP。建议在路由器中为设备设置 DHCP 地址保留。如果 IP
发生变化，使用新 IP 重新运行脚本即可；脚本复用原根 CA，因此 iPhone 无需重复安装信任。

服务器证书到期前同样重新运行脚本。若要撤销信任，在 iPhone 的“VPN 与设备管理”中
删除该描述文件；设备的 `8080 HTTP` 不受影响。
