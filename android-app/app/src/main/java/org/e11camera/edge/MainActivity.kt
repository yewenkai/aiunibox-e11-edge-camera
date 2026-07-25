package org.e11camera.edge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var permissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermission()
        // 有权限则自动启动监控服务（适配无触摸屏的带屏设备）
        if (permissionGranted.value) {
            startMonitorService()
        }
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen(
                        ip = NetworkUtil.getIpAddress(this),
                        permissionGranted = permissionGranted.value,
                        onRequestPermission = { requestPermission() },
                        onStartService = { startMonitorService() },
                        onStopService = { stopMonitorService() }
                    )
                }
            }
        }
    }

    private fun checkPermission() {
        permissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkPermission()
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMonitorService() {
        stopService(Intent(this, MonitorService::class.java))
    }
}

@Composable
private fun AppScreen(
    ip: String,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var serviceRunning by remember { mutableStateOf(MonitorService.instance != null) }
    val url = remember(ip) { "http://$ip:${MonitorService.PORT}" }

    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = MonitorService.instance?.isWebRunning() == true
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 摄像头本地回显（仅前台时显示）
        if (serviceRunning) {
            CameraPreview(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .aspectRatio(4f / 3f)
            )
            Spacer(Modifier.height(16.dp))
        }

        Icon(
            imageVector = Icons.Default.Videocam,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "AIUniBOX-E11 Edge Camera",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))

        // 权限提示
        if (!permissionGranted) {
            Text(
                "需要摄像头权限",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermission) {
                Text("授予权限")
            }
            Spacer(Modifier.height(24.dp))
        }

        // 访问地址卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("局域网访问地址", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = url,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "浏览器打开即可查看画面并控制云台",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // 启动/停止按钮
        Button(
            onClick = {
                if (serviceRunning) onStopService() else onStartService()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = if (serviceRunning) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(
                if (serviceRunning) "停止监控服务" else "启动监控服务",
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            if (serviceRunning) "监控服务正在运行，关闭此页面不会停止监控"
            else "首次转动云台会请求 root 授权（Magisk），请勾选「记住」",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 摄像头本地回显（仅 App 前台时显示）。
 * 用 SurfaceView 直接渲染 Camera2 输出，零拷贝、低延迟。
 */
@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // 把 Surface 交给 MonitorService，触发摄像头重新绑定
                        val svc = MonitorService.instance ?: return
                        val streamer = svc.getStreamer() ?: return
                        streamer.previewSurface = holder.surface
                        // 重新启动摄像头以包含预览输出
                        svc.restartCamera()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // 移除预览输出
                        val svc = MonitorService.instance ?: return
                        val streamer = svc.getStreamer() ?: return
                        streamer.previewSurface = null
                        svc.restartCamera()
                    }
                })
            }
        },
        modifier = modifier
    )
}
