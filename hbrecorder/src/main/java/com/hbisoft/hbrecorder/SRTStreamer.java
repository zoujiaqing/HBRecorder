package com.hbisoft.hbrecorder;

import static android.media.MediaCodec.CONFIGURE_FLAG_ENCODE;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;

public class SRTStreamer {
    private Context context;
    private MediaCodec mediaCodec;
    private MediaProjection mMediaProjection;
    private boolean isStreaming = false;
    private Integer height;
    private Integer width;

    // 用于连接 FFmpegKit 的管道
    private PipedOutputStream pipedOutputStream;
    private PipedInputStream pipedInputStream;

    public SRTStreamer(Context context, MediaProjection mediaProjection, int width, int height) {

        this.context = context;
        this.mMediaProjection = mediaProjection;
        this.width = width;
        this.height = height;
        this.pipedOutputStream = new PipedOutputStream();
        try {
            // 创建管道输入流连接
            this.pipedInputStream = new PipedInputStream(pipedOutputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public void startStreaming() {
        try {
            // 使用软件编码器，避免硬件加速
            String softwareEncoderName = findSoftwareEncoder("video/avc");
            if (softwareEncoderName != null) {
                mediaCodec = MediaCodec.createByCodecName(softwareEncoderName);
            } else {
                mediaCodec = MediaCodec.createEncoderByType("video/avc");
            }

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);

            format.setInteger(MediaFormat.KEY_FRAME_RATE, 12);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000); // 降低比特率

            // 强制使用软件支持的颜色格式
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar); // 使用软件支持的颜色格式
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // I-frame 间隔

            try {
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface inputSurface = mediaCodec.createInputSurface();
                mMediaProjection.createVirtualDisplay("test", width, height, 2,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC, inputSurface, null, null
                );
                mediaCodec.start();
            } catch (IllegalStateException e) {
                Log.e("MediaCodec", "IllegalStateException during MediaCodec configuration: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                Log.e("MediaCodec", "IllegalArgumentException during MediaCodec configuration: " + e.getMessage());
            }

//            FFmpegKit.executeAsync("-codecs", session -> {
//                String output = session.getOutput();
//                Log.d("FFmpeg", "Available codecs: " + output);
//            });

            FFmpegKit.executeAsync("-protocols", session -> {
                String output = session.getAllLogsAsString();
                Log.d("FFmpeg", "Supported Protocols: \n" + output);
            });


            // 设置 RTP 推流目标
            String rtpServerUrl = "rtp://172.20.10.5:8000"; // 替换为你的 RTP 服务器 IP 和端口

            // 创建 FFmpeg 推流命令
            String sdpFilePath = context.getExternalFilesDir(null) + "/rtp_stream.sdp";
            String ffmpegCommand = String.format(
                    "-f rawvideo -pix_fmt yuv420p -s %dx%d -r 12 -i pipe:0 -c:v libx264 -preset ultrafast -f rtp -loglevel debug -sdp_file %s %s",
                    width, height, sdpFilePath, rtpServerUrl
            );

            // 执行 FFmpeg 命令进行推流
            FFmpegKit.executeAsync(ffmpegCommand, session -> {
                ReturnCode returnCode = session.getReturnCode();
                if (ReturnCode.isSuccess(returnCode)) {
                    Log.d("FFmpeg", "推流成功");
                } else {
                    Log.e("FFmpeg", "推流失败: " + session.getFailStackTrace());
                }
            });

            isStreaming = true;

            // 开启编码线程
            new Thread(this::encodeAndStream).start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void encodeAndStream() {
        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isStreaming) {
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex >= 0) {
                ByteBuffer encodedData = outputBuffers[outputBufferIndex];

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // 如果是 codec config buffer，跳过
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0) {
                    // 准备好数据，发送到 FFmpegKit 的输入
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);

                    // 将 encodedData 写入 pipedOutputStream
                    try {
                        byte[] buffer = new byte[bufferInfo.size];
                        encodedData.get(buffer);
                        pipedOutputStream.write(buffer);
                        pipedOutputStream.flush();
                    } catch (IOException e) {
                        Log.e("SRTStreamer", "数据写入管道失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            }
        }

        // 停止推流时关闭输出流
        try {
            pipedOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopStreaming() {
        isStreaming = false;
        mediaCodec.stop();
        mediaCodec.release();
    }
}