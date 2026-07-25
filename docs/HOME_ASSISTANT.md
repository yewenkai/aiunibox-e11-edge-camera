# Home Assistant 场景建议

## 推荐仪表盘

在同一张网格卡中放置：

1. `camera.*摄像头` 实时画面
2. `button.*画面向左`、`button.*画面向右`
3. `button.*回到常用位置`
4. `light.*补光灯`
5. `select.*夜视场景`
6. `switch.*隐私模式`
7. `binary_sensor.*正在观看`

首次使用时，把镜头转到日常方向，依次按“当前位置设为零点”和“保存当前位置”。

## 在家自动隐私

下面是自动化逻辑示例。实体 ID 以 Home Assistant 实际生成结果为准：

```yaml
alias: 中屏摄像头在家隐私
triggers:
  - trigger: state
    entity_id: person.yewenkai
actions:
  - choose:
      - conditions: "{{ trigger.to_state.state == 'home' }}"
        sequence:
          - action: switch.turn_on
            target:
              entity_id: switch.e11_privacy
    default:
      - action: switch.turn_off
        target:
          entity_id: switch.e11_privacy
mode: restart
```

## 扫地机异常观察

先把摄像头对准扫地机充电座并保存“常用位置”，再创建自动化：

```yaml
alias: 扫地机异常时查看充电座
triggers:
  - trigger: state
    entity_id: vacuum.mijia_m40
    to: error
actions:
  - action: button.press
    target:
      entity_id: button.e11_go_home
  - delay: "00:00:03"
  - action: camera.snapshot
    target:
      entity_id: camera.e11_camera
    data:
      filename: /config/www/e11-vacuum-error.jpg
mode: single
```

## 观看提示

设备侧默认在 MediaMTX 检测到读取者时点亮 camera LED。Home Assistant 中还可以用
“正在观看”二进制传感器触发手机通知或状态灯，但不建议在频繁打开仪表盘的场景中
启用通知，以免产生噪声。

## 已知边界

- 云台只有水平旋转，没有俯仰。
- 云台位置是软件累计值，外部直接执行厂商命令会造成漂移。
- API Token 不覆盖 MediaMTX 视频端口，需要单独配置视频鉴权或网络隔离。
- 隐私模式会关闭 Camera2 与 RTSP 发布，但 HTTP 控制服务仍保持在线。
