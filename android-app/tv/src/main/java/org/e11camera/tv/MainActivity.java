package org.e11camera.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.net.URI;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;

/** 电视端原生 WebRTC 接收器，RTSP 可在菜单手动选择。 */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private static final String DEFAULT_ENDPOINT = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NativeRtcPlayer rtc;
    private PlayerView nativeView;
    private ExoPlayer nativePlayer;
    private boolean useRtsp;
    private int playbackGeneration;
    private TextView status;
    private boolean active;
    private boolean menuOpen;
    private boolean muted = true;
    private boolean fill;
    private long lastProgress;
    private double lastTime = -1;
    private String endpoint;
    private final java.util.concurrent.ExecutorService motorRequests = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean motorBusy;
    private long lastMotorAt = -1000;
    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (useRtsp) {
                if (nativePlayer != null) {
                    double position = nativePlayer.getCurrentPosition();
                    if (nativePlayer.isPlaying() && position != lastTime) {
                        lastTime = position;
                        lastProgress = SystemClock.elapsedRealtime();
                    } else if (SystemClock.elapsedRealtime() - lastProgress > 20000) loadStream();
                }
                handler.postDelayed(this, 3000);
                return;
            }
            if (SystemClock.elapsedRealtime() - rtc.lastFrame > 20000) loadStream();
            handler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        endpoint = getPreferences(MODE_PRIVATE).getString("endpoint", DEFAULT_ENDPOINT);
        if (endpoint == null || endpoint.trim().isEmpty()) endpoint = DEFAULT_ENDPOINT;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        rtc = new NativeRtcPlayer(this, message -> { if (active && !useRtsp) showStatus(message); });
        // MATCH_PARENT 会以 EXACTLY 测量并覆盖 WebRTC 的等比尺寸，造成裁剪。
        root.addView(rtc.view, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        nativeView = new PlayerView(this);
        nativeView.setUseController(false);
        nativeView.setVisibility(View.GONE);
        root.addView(nativeView, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(18);
        status.setPadding(24, 12, 24, 12);
        status.setBackgroundColor(0xCC102333);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = 24;
        root.addView(status, params);
        setContentView(root);
    }

    private void loadStream() {
        if (endpoint.isEmpty()) return;
        playbackGeneration++;
        lastProgress = SystemClock.elapsedRealtime();
        lastTime = -1;
        releaseNative();
        rtc.stop();
        if (useRtsp) { startRtsp(); return; }
        nativeView.setVisibility(View.GONE);
        rtc.view.setVisibility(View.VISIBLE);
        rtc.start(endpoint);
        rtc.fit(fill);
        showStatus("正在连接原生 WebRTC · 确认键打开菜单");
    }

    private void applyVideoOptions() {
        if (nativePlayer != null) nativePlayer.setVolume(muted ? 0f : 1f);
        nativeView.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        rtc.fit(fill);
    }

    private void startRtsp() {
        rtc.view.setVisibility(View.GONE);
        nativeView.setVisibility(View.VISIBLE);
        URI web = URI.create(endpoint);
        String path = web.getPath().replaceAll("/+$", "");
        String host = web.getHost();
        if (host.contains(":")) host = "[" + host + "]";
        String url = "rtsp://" + host + ":8554" + path;
        nativePlayer = new ExoPlayer.Builder(this,
            new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(300, 1500, 150, 300).build())
            .build();
        nativeView.setPlayer(nativePlayer);
        int run = playbackGeneration;
        nativePlayer.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (run != playbackGeneration) return;
                android.util.Log.i("BabyMonitor", "RTSP first frame rendered");
                showStatus("原生 RTSP 已连接 · 确认键打开菜单");
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (run != playbackGeneration) return;
                android.util.Log.e("BabyMonitor", "RTSP playback failed", error);
                showStatus("播放失败，正在重试 · " + error.getErrorCodeName());
                handler.postDelayed(() -> { if (active && run == playbackGeneration) loadStream(); }, 3000);
            }
        });
        nativePlayer.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
            .setTimeoutMs(8000).createMediaSource(MediaItem.fromUri(url)));
        applyVideoOptions();
        nativePlayer.prepare();
        nativePlayer.play();
        showStatus("正在连接原生 RTSP…");
    }

    private void releaseNative() {
        nativeView.setPlayer(null);
        if (nativePlayer != null) { nativePlayer.release(); nativePlayer = null; }
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideStatus);
        handler.postDelayed(hideStatus, 7000);
    }

    private void menu() {
        if (menuOpen) return;
        menuOpen = true;
        new AlertDialog.Builder(this).setTitle("宝宝监控 · " + (useRtsp ? "原生 RTSP" : "WebRTC"))
            .setItems(new String[]{"重新连接", "设置设备地址", fill ? "显示完整画面" : "铺满屏幕（裁剪边缘）",
                muted ? "打开流内声音" : "静音", "退出应用", useRtsp ? "重新尝试 WebRTC" : "切换原生 RTSP"}, (dialog, which) -> {
                menuOpen = false;
                switch (which) {
                    case 0: loadStream(); break;
                    case 1: editAddress(); break;
                    case 2: fill = !fill; applyVideoOptions(); break;
                    case 3: muted = !muted; applyVideoOptions(); showStatus(muted ? "已静音" : "已打开声音（视频源需包含音轨）"); break;
                    case 4: finish(); break;
                    case 5: useRtsp = !useRtsp; loadStream(); break;
                    default: break;
                }
            }).setOnDismissListener(dialog -> menuOpen = false).show();
    }

    private void editAddress() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(endpoint);
        input.setHint("http://设备IP:8080 或 http://设备IP:8889/cam/");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置设备地址")
            .setMessage("输入控制页地址将自动使用同设备的 8889/cam/ WebRTC。也可输入完整 WebRTC 播放页地址。")
            .setView(input).setPositiveButton("保存并播放", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String raw = input.getText().toString().trim();
                if (!raw.contains("://")) raw = "http://" + raw;
                URI uri = new URI(raw);
                if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
                int port = uri.getPort();
                String path = uri.getPath();
                if (port == -1 || port == 8080) { port = 8889; path = "/cam/"; }
                else if (path == null || path.isEmpty() || path.equals("/")) path = "/cam/";
                if (!path.endsWith("/")) path += "/";
                endpoint = new URI("http", null, uri.getHost(), port, path, null, null).toString();
                useRtsp = false;
                getPreferences(MODE_PRIVATE).edit().putString("endpoint", endpoint).apply();
                loadStream();
                dialog.dismiss();
            } catch (Exception exception) {
                input.setError("请输入有效的局域网 HTTP 地址");
            }
        }));
        dialog.show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // 只响应点按首次事件；系统长按重复事件不积压运动命令。
            if (event.getRepeatCount() == 0 && active && !menuOpen) moveMotor(keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) menu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void moveMotor(boolean left) {
        long now = SystemClock.elapsedRealtime();
        if (motorBusy || now - lastMotorAt < 450) return;
        final String address;
        try {
            address = new URI("http", null, URI.create(endpoint).getHost(), 8080, "/api/motor",
                "dir=" + (left ? "left" : "right") + "&steps=83&speed=400", null).toString();
        } catch (Exception error) { showStatus("云台控制地址无效"); return; }
        motorBusy = true;
        lastMotorAt = now;
        motorRequests.execute(() -> {
            java.net.HttpURLConnection connection = null;
            String message;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                String body;
                try (java.io.InputStream input = connection.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    body = output.toString("UTF-8");
                }
                org.json.JSONObject result = new org.json.JSONObject(body);
                message = result.optBoolean("ok") ? (left ? "◀ 云台向左" : "云台向右 ▶")
                    : ("motor_busy".equals(result.optString("error")) ? "云台正在转动，请稍后再按" : "云台未执行：" + result.optString("error", "未知错误"));
            } catch (Exception error) { message = "云台控制失败：" + error.getMessage(); }
            finally { if (connection != null) connection.disconnect(); motorBusy = false; }
            final String feedback = message;
            handler.post(() -> { if (active) showStatus(feedback); });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        loadStream();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 3000);
    }

    @Override protected void onPause() {
        active = false;
        playbackGeneration++;
        handler.removeCallbacksAndMessages(null);
        releaseNative();
        rtc.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        rtc.release();
        motorRequests.shutdown();
        super.onDestroy();
    }
}
