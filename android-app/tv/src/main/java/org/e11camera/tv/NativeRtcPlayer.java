package org.e11camera.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.webrtc.*;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 只接收视频的 WHEP 客户端；收集 ICE 后发送完整 offer，关闭时删除会话。 */
final class NativeRtcPlayer {
    interface StatusListener { void accept(String message); }
    final SurfaceViewRenderer view;
    volatile long lastFrame;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final EglBase egl = EglBase.create();
    private final PeerConnectionFactory factory;
    private final StatusListener status;
    private PeerConnection peer;
    private VideoTrack track;
    private URI session;
    private int generation;
    private boolean sent;
    private volatile boolean first;
    private final VideoSink sink;

    NativeRtcPlayer(Context context, StatusListener status) {
        this.status = status;
        view = new SurfaceViewRenderer(context);
        view.init(egl.getEglBaseContext(), null);
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        view.setEnableHardwareScaler(true);
        sink = frame -> {
            lastFrame = SystemClock.elapsedRealtime();
            if (!first) {
                first = true;
                Log.i("BabyMonitor", "WebRTC first decoded frame " + frame.getBuffer().getWidth() + "x" + frame.getBuffer().getHeight());
                main.post(() -> status.accept("原生 WebRTC 已连接"));
            }
            view.onFrame(frame);
        };
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
        DefaultVideoDecoderFactory decoder = new DefaultVideoDecoderFactory(egl.getEglBaseContext());
        for (VideoCodecInfo codec : decoder.getSupportedCodecs()) Log.i("BabyMonitor", "Native decoder " + codec.name + " " + codec.params);
        factory = PeerConnectionFactory.builder().setVideoDecoderFactory(decoder)
            .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), false, true)).createPeerConnectionFactory();
    }

    void start(String endpoint) {
        stop();
        int run = generation;
        sent = false; first = false; lastFrame = SystemClock.elapsedRealtime();
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        URI url = URI.create(endpoint).resolve("whep");
        peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState state) { }
            public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                Log.i("BabyMonitor", "Native ICE " + state);
            }
            public void onIceConnectionReceivingChange(boolean receiving) { }
            public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) main.post(() -> offer(run, url));
            }
            public void onIceCandidate(IceCandidate candidate) { }
            public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
            public void onAddStream(MediaStream stream) { }
            public void onRemoveStream(MediaStream stream) { }
            public void onDataChannel(DataChannel channel) { }
            public void onRenegotiationNeeded() { }
            public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                main.post(() -> {
                    if (run != generation || peer == null) return;
                    if (receiver.track() instanceof VideoTrack) {
                        track = (VideoTrack) receiver.track(); track.addSink(sink);
                    }
                });
            }
        });
        if (peer == null) { status.accept("无法创建原生 WebRTC"); return; }
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        peer.createOffer(new Observer() {
            public void onCreateSuccess(SessionDescription sdp) {
                main.post(() -> {
                    if (run != generation || peer == null) return;
                    peer.setLocalDescription(new Observer() {
                        public void onSetSuccess() { main.postDelayed(() -> offer(run, url), 5000); }
                        public void onSetFailure(String error) { error(run, error); }
                    }, sdp);
                });
            }
            public void onCreateFailure(String error) { error(run, error); }
        }, new MediaConstraints());
    }

    private void error(int run, String error) {
        main.post(() -> { if (run == generation) { Log.e("BabyMonitor", error); status.accept("WebRTC: " + error); } });
    }

    private void offer(int run, URI url) {
        if (run != generation || peer == null || sent || peer.getLocalDescription() == null) return;
        sent = true;
        String sdp = peer.getLocalDescription().description;
        network.execute(() -> {
            HttpURLConnection c = null;
            URI resource = null;
            try {
                c = (HttpURLConnection) url.toURL().openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/sdp"); c.setDoOutput(true);
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(sdp.getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code != 201) throw new java.io.IOException("WHEP HTTP " + code);
                if (c.getHeaderField("Location") != null) resource = url.resolve(c.getHeaderField("Location"));
                String answer;
                try (java.io.InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096]; int n;
                    while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                    answer = out.toString("UTF-8");
                }
                URI remote = resource;
                main.post(() -> {
                    if (run != generation || peer == null) { delete(remote); return; }
                    session = remote;
                    peer.setRemoteDescription(new Observer() {
                        public void onSetFailure(String error) { error(run, error); }
                    }, new SessionDescription(SessionDescription.Type.ANSWER, answer));
                });
            } catch (Exception e) { delete(resource); error(run, e.toString()); }
            finally { if (c != null) c.disconnect(); }
        });
    }

    // 独立清理线程避免 Activity 退出时丢失稍后返回的会话。
    private void delete(URI remote) {
        if (remote == null) return;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) remote.toURL().openConnection(); c.setRequestMethod("DELETE");
                c.setConnectTimeout(3000); c.setReadTimeout(3000); c.getResponseCode();
            } catch (Exception ignored) { } finally { if (c != null) c.disconnect(); }
        }, "whep-cleanup").start();
    }
    void fit(boolean fill) { view.setScalingType(fill ? RendererCommon.ScalingType.SCALE_ASPECT_FILL : RendererCommon.ScalingType.SCALE_ASPECT_FIT); }
    void stop() {
        generation++;
        if (track != null) { track.removeSink(sink); track = null; }
        if (peer != null) { peer.close(); peer.dispose(); peer = null; }
        delete(session); session = null;
    }
    void release() { stop(); view.release(); factory.dispose(); egl.release(); network.shutdown(); }
    private static class Observer implements SdpObserver {
        public void onCreateSuccess(SessionDescription sdp) { }
        public void onSetSuccess() { }
        public void onCreateFailure(String error) { }
        public void onSetFailure(String error) { }
    }
}
