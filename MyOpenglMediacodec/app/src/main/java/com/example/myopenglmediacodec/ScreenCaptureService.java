package com.example.myopenglmediacodec;

import static com.example.myopenglmediacodec.MyApplication.CHANNEL_ID;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.example.myopenglmediacodec.surface.InputSurface;
import com.example.myopenglmediacodec.surface.TextureRenderer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";

    private static final int CMD_START_RECORD = 0;
    private static final int CMD_STOP_RECORD = 1;

    private ServiceHandler mServiceHandler;
    private Handler mEncoderHandler = null;
    private Intent mProjData = null;
    private boolean mIsEnableGetFrame = false;

    private ScheduledExecutorService mFrameRenderExecutor = null;

    public ScreenCaptureService() {
        mFrameRenderExecutor = Executors.newScheduledThreadPool(1);
        mServiceHandler = new ServiceHandler(this);
        mEncoderHandler = getEncoderHandler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sendNotification("Recording ...");

        String action = intent.getAction();
        switch (action) {
            case "start": {
                if (!mIsEnableGetFrame) {
                    mIsEnableGetFrame = true;
                    mProjData = intent.getParcelableExtra("com.ns.pData");

                    mFrameRenderExecutor.scheduleAtFixedRate(() -> {
                        renderFrame();
                    }, 0, 1000 / 30, TimeUnit.MILLISECONDS);
                }
            }
            break;
            case "stop": {
                if (mIsEnableGetFrame) {
                    mIsEnableGetFrame = false;
                    while (mServiceHandler.isPrepare()) {
                        try {
                            Thread.sleep(1000 / 30);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    mFrameRenderExecutor.shutdown();
                }
            }
            break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mIsEnableGetFrame = false;
        mServiceHandler = null;
        mEncoderHandler = null;
        mProjData = null;
    }

    private void sendNotification(String msg) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notify = new NotificationCompat.Builder(this,
                CHANNEL_ID)
                .setContentTitle("Recorder")
                .setContentText(msg)
                .setSmallIcon(R.drawable.ic_notification_24dp)
                .setContentIntent(pIntent)
                .build();

        startForeground(1, notify);
    }

    private Handler getEncoderHandler() {
        HandlerThread t = new HandlerThread("test_encoder_thread");
        t.start();
        return new Handler(t.getLooper());
    }

    private void renderFrame() {
        if (mIsEnableGetFrame) {
            if (!mServiceHandler.isPrepare()) {
                mServiceHandler.startCapture();
            }
            mServiceHandler.getFrame();
        } else {
            mServiceHandler.stopCapture();
        }
    }

    private static class ServiceHandler {

        private static final int mGetFrameDelay = 1000 / 30; //ms
        private MediaCodec mEncoder;
        private MediaMuxer mMuxer;
        private MediaProjection mMediaProj;

        private VirtualDisplay mVirtualDisplay;
        private final String mVideoURL = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + File.separator + "recorder.mp4";

        private boolean mIsPrepare = false;
        private boolean mIsRecording = false;
        private ScreenCaptureService mScreenCaptureService = null;

        private boolean mIsPrepareOpengl = false;
        private InputSurface mCodecInputSurface;
        private TextureRenderer mTexRenderer;

        public ServiceHandler(ScreenCaptureService service) {
            mScreenCaptureService = service;
        }

        public void startCapture() {
            Log.e(TAG, "startCapture");


            //config media codec encoder
            String mMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
            try {
                mEncoder = MediaCodec.createEncoderByType(mMimeType);
                mMuxer = new MediaMuxer(mVideoURL, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                Log.i(TAG, "Save file to: " + mVideoURL);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            mEncoder.setCallback(new MediaCodec.Callback() {
                int track = -1;
                long initialPts = 0;

                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    Log.e(TAG, "onOutputBufferAvailable ..." + info.presentationTimeUs);

                    if (info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                        mMuxer.writeSampleData(track, codec.getOutputBuffer(index), info);
                        mMuxer.stop();
                        mMuxer.release();
                        mMuxer = null;

                        mEncoder.stop();
                        mEncoder.release();
                        mEncoder = null;

                        mMediaProj.stop();
                        mVirtualDisplay.release();
                        mMediaProj = null;
                        mVirtualDisplay = null;

                        mTexRenderer = null;
                        mCodecInputSurface.release();
                        mCodecInputSurface = null;
                        mIsPrepareOpengl = false;

                        //stop ScreenCaptureService
                        mScreenCaptureService.stopSelf();

                        mIsRecording = false;
                        mIsPrepare = false;
                        track = -1;
                        Log.i(TAG, "end of stream: mIsRecording = " + mIsRecording);
                        return;
                    }

                    if (info.presentationTimeUs != 0) {
                        if (initialPts == 0) {
                            initialPts = info.presentationTimeUs;
                            info.presentationTimeUs = 100;
                        } else {
                            info.presentationTimeUs -= initialPts;
                        }
                    }

                    ByteBuffer encoderBuffer = codec.getOutputBuffer(index);
                    if (track >= 0) {
                        mMuxer.writeSampleData(track, encoderBuffer, info);
                    }
                    codec.releaseOutputBuffer(index, false);
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    track = mMuxer.addTrack(format);
                    if (track >= 0) {
                        mMuxer.start();
                        mIsRecording = true;
                        Log.i(TAG, "start record");
                    }
                }
            }, mScreenCaptureService.mEncoderHandler);

            DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
            MediaFormat format = MediaFormat.createVideoFormat(mMimeType, m.widthPixels, m.heightPixels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = mEncoder.createInputSurface();
            mEncoder.start();
            prepareOpengl(mScreenCaptureService, inputSurface);
            //config media projection
            mMediaProj = ((MediaProjectionManager) mScreenCaptureService.getSystemService(MEDIA_PROJECTION_SERVICE))
                    .getMediaProjection(-1, mScreenCaptureService.mProjData);
            assert mMediaProj != null;

            mVirtualDisplay = mMediaProj.createVirtualDisplay(
                    "recorderVirtualDisplay",
                    m.widthPixels,
                    m.heightPixels,
                    m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mTexRenderer.getSurface(), null, null);

            mIsPrepare = true;
        }

        public void stopCapture() {
            Log.i(TAG, "stopCapture ...");
            if (mEncoder == null) {
                return;
            }
            mEncoder.signalEndOfInputStream();
        }

        public boolean isPrepare() {
            return mIsPrepare;
        }

        private void prepareOpengl(ScreenCaptureService service, Surface surface) {
            if (mIsPrepareOpengl) return;
            mCodecInputSurface = new InputSurface(surface);
            DisplayMetrics m = Resources.getSystem().getDisplayMetrics();

            //MUST call makeCurrent to force enable opengl context for setup texture render
            mCodecInputSurface.makeCurrent();
            mTexRenderer = new TextureRenderer(m.widthPixels, m.heightPixels);
            mCodecInputSurface.setPresentationTime(0);
            mCodecInputSurface.swapBuffers();
            mIsPrepareOpengl = true;
        }

        private void getFrame() {
            if (mVirtualDisplay == null) {
                return;
            }

            drawFrame();
        }

        private void drawFrame() {
            if (!mIsPrepareOpengl) return;

            mCodecInputSurface.makeCurrent();
            mTexRenderer.updateFrame();
            mTexRenderer.drawFrame();
            mCodecInputSurface.setPresentationTime(mTexRenderer.getSurfaceTexture().getTimestamp());
            mCodecInputSurface.swapBuffers();
        }
    }

}