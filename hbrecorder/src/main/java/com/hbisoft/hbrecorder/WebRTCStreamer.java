package com.hbisoft.hbrecorder;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import org.webrtc.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class WebRTCStreamer {
    private Context context;
    private MediaCodec mediaCodec;
    private MediaProjection mMediaProjection;
    private boolean isStreaming = false;
    private Integer height;
    private Integer width;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoTrack videoTrack;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private Surface inputSurface;
    private EglBase eglBase; // 添加 EglBase 成员变量

    private void initializePeerConnectionFactory() {
        // 创建 EglBase 实例
        eglBase = EglBase.create();

        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        // 创建 PeerConnectionFactory 实例
        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    private void createVideoCapturer() {
        // 创建视频捕获器
        videoCapturer = createScreenCapturer();

        // 使用 EglBase 上下文来创建 SurfaceTextureHelper
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        inputSurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
    }

    public WebRTCStreamer(Context context, MediaProjection mediaProjection, int width, int height) {
        this.context = context;
        this.mMediaProjection = mediaProjection;
        this.width = width;
        this.height = height;
    }

    public void startStreaming() {
        try {
            // 初始化 WebRTC
            initializePeerConnectionFactory();

            // 创建视频捕获器
            createVideoCapturer();

            // 创建编码器
            initializeMediaCodec();

            // 设置虚拟显示器
            Surface inputSurface = mediaCodec.createInputSurface();
            mMediaProjection.createVirtualDisplay("test", width, height, 2,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, inputSurface, null, null
            );

            // 初始化视频轨道
            createVideoTrack();

            // 启动推流
            startWebRTCStream();

            // 开启编码线程
            new Thread(this::encodeAndStream).start();

            isStreaming = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private VideoCapturer createScreenCapturer() {
        if (mMediaProjection == null) {
            Log.e("WebRTCStreamer", "MediaProjection is not initialized.");
            return null;
        }

        // 假设权限结果是通过 Intent 获取
        Intent mediaProjectionPermissionResultData = new Intent(); // 使用正确的方式获取 Intent 数据
        MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.d("WebRTCStreamer", "MediaProjection stopped.");
            }
        };

        return new ScreenCapturerAndroid(
                mediaProjectionPermissionResultData, // 传递 Intent 类型的数据
                mediaProjectionCallback               // 传递 MediaProjection.Callback 回调
        );
    }

    private void initializeMediaCodec() throws IOException {
        // 创建 MediaCodec 编码器
        String softwareEncoderName = findSoftwareEncoder("video/avc");
        if (softwareEncoderName != null) {
            mediaCodec = MediaCodec.createByCodecName(softwareEncoderName);
        } else {
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        }

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 12);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mediaCodec.start();
    }

    private void createVideoTrack() {
        // 创建视频源并创建视频轨道
        VideoSource videoSource = peerConnectionFactory.createVideoSource(false);
        videoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource);

        // 将 VideoCapturer 和 SurfaceTextureHelper 绑定到 VideoSource
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        videoCapturer.startCapture(width, height, 30);
    }

    private void startWebRTCStream() {
        // 设置配置并建立 PeerConnection
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(new ArrayList<>());
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnectionAdapter("PeerConnectionAdapter") {
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                super.onIceConnectionChange(newState);
                Log.d("WebRTCStreamer", "ICE connection state: " + newState);
            }
        });

        // 将视频轨道添加到 PeerConnection 中
        MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);

        peerConnection.addStream(mediaStream);
        Log.d("WebRTCStreamer", "WebRTC stream started");
    }

    private String findSoftwareEncoder(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (codecInfo.isEncoder() && codecInfo.getName().contains("software")) {
                String[] types = codecInfo.getSupportedTypes();
                for (String type : types) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        return codecInfo.getName();
                    }
                }
            }
        }
        return null;
    }

    private void encodeAndStream() {
        // 处理编码后的数据，并发送到 WebRTC PeerConnection
        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isStreaming) {
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex >= 0) {
                ByteBuffer encodedData = outputBuffers[outputBufferIndex];
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size != 0) {
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                    // 将编码后的数据发送到 WebRTC
                    videoTrack.addSink(new VideoSink() {
                        @Override
                        public void onFrame(VideoFrame frame) {
                            Log.d("WebRTCStreamer", "Encoded frame sent: " + frame);
                        }
                    });
                }
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            }
        }
    }

    public void stopStreaming() {
        isStreaming = false;
        mediaCodec.stop();
        mediaCodec.release();
        peerConnection.close();
        Log.d("WebRTCStreamer", "Streaming stopped");
    }

    // 创建 PeerConnection 的适配器类
    private static class PeerConnectionAdapter implements PeerConnection.Observer {
        private String tag;

        public PeerConnectionAdapter(String tag) {
            this.tag = tag;
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.d(tag, "ICE connection state: " + newState);
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(tag, "Signaling state changed: " + signalingState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(tag, "ICE connection receiving change: " + receiving);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Log.d(tag, "ICE gathering state changed: " + newState);
        }

        @Override
        public void onAddStream(MediaStream stream) {
            Log.d(tag, "Stream added: " + stream.getId());
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
            Log.d(tag, "Stream removed: " + stream.getId());
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(tag, "Data channel created: " + dataChannel.label());
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(tag, "Renegotiation needed.");
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Log.d(tag, "New ICE candidate: " + candidate.toString());
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Log.d(tag, "ICE candidates removed.");
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            Log.d(tag, "Track received: " + transceiver.getMid());
        }
    }
}