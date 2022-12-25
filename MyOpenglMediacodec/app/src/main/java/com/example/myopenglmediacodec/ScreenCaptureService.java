package com.example.myopenglmediacodec;

import static com.example.myopenglmediacodec.MyApplication.CHANNEL_ID;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
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
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.example.myopenglmediacodec.surface.InputSurface;
import com.example.myopenglmediacodec.surface.TextureRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";

    private static final int CMD_START_RECORD = 0;
    private static final int CMD_STOP_RECORD = 1;

    private ServiceHandler mHandler;
    private Handler mEncoderHandler = null;
    private Intent mProjData;
    private boolean mIsEnableGetFrame = false;

    public ScreenCaptureService() {
        HandlerThread thread = new HandlerThread("test_record_screen_thread");
        thread.start();
        mHandler = new ServiceHandler(thread.getLooper(), this);

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
                    Message m = mHandler.obtainMessage();
                    m.what = CMD_START_RECORD;
                    mHandler.sendMessage(m);
                }
            }
            break;
            case "stop": {
//                Message m = mHandler.obtainMessage();
//                m.what = CMD_STOP_RECORD;
//                mHandler.sendMessage(m);
                mIsEnableGetFrame = false;
            }
            break;
        }

        return START_NOT_STICKY;
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

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mIsEnableGetFrame = false;
        mHandler = null;
        mEncoderHandler = null;
        mProjData = null;
    }

    private static class ServiceHandler extends Handler {

        private static final int mGetFrameDelay = 1000 / 30; //ms
        private MediaCodec mEncoder;
        private MediaMuxer mMuxer;
        private MediaProjection mMediaProj;
        private ImageReader mImageReader;

        private VirtualDisplay mVirtualDisplay;
        private final String mVideoURL = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()
                + File.separator + "Recorder_Video.mp4";

        private boolean mIsRecording = false;
//        private ScreenCaptureService mScreenCaptureService = null;

        private boolean mIsPrepareOpengl = false;
        private InputSurface mCodecInputSurface;
        private TextureRenderer mTexRenderer;

        private final WeakReference<ScreenCaptureService> mScreenCaptureService;


        public ServiceHandler(@NonNull Looper looper, ScreenCaptureService s) {
            super(looper);
            mScreenCaptureService = new WeakReference<>(s);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);

            switch (msg.what) {
                //start capturing
                case CMD_START_RECORD:
                    startCapture();
                    break;
                //stop capture
//                case CMD_STOP_RECORD:
//                    stopCapture();
//                    break;
            }
        }

        private void startCapture() {
            Log.e(TAG, "startCapture");
            ScreenCaptureService service = mScreenCaptureService.get();
            if (service == null) {
                return;
            }

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
                        service.stopSelf();

                        mIsRecording = false;
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
            }, service.mEncoderHandler);

            DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
            MediaFormat format = MediaFormat.createVideoFormat(mMimeType, m.widthPixels, m.heightPixels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = mEncoder.createInputSurface();
            mEncoder.start();
            prepareOpengl(service, inputSurface);
            //config media projection
            mMediaProj = ((MediaProjectionManager) service.getSystemService(MEDIA_PROJECTION_SERVICE))
                    .getMediaProjection(-1, service.mProjData);
            assert mMediaProj != null;

            mImageReader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 1);
            mVirtualDisplay = mMediaProj.createVirtualDisplay(
                    "recorderVirtualDisplay",
                    m.widthPixels,
                    m.heightPixels,
                    m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);

            getFrame(service);
        }


//
//        private void stopCapture() {
//            Log.i(TAG, "stopCapture ...");
//            if (mEncoder == null) {
//                return;
//            }
//            mEncoder.signalEndOfInputStream();
//        }


        private void prepareOpengl(ScreenCaptureService service, Surface surface) {
            if (mIsPrepareOpengl) return;
            mCodecInputSurface = new InputSurface(surface, false, false);

            //MUST call makeCurrent to force enable opengl context for setup texture render
            mCodecInputSurface.makeCurrent();
            mTexRenderer = new TextureRenderer(service);
            mTexRenderer.init();
            DisplayMetrics m = Resources.getSystem().getDisplayMetrics();//set screen size
            mTexRenderer.createTexture(m.widthPixels, m.heightPixels);
            mCodecInputSurface.setPresentationTime(0);
            mCodecInputSurface.swapBuffers();
            mIsPrepareOpengl = true;
        }

        private void getFrame(ScreenCaptureService service) {
            Log.e(TAG, "getFrame1  mImageReader = " + mImageReader + ", mVirtualDisplay = " + mVirtualDisplay);
            if (mImageReader == null || mVirtualDisplay == null) {
                return;
            }

            while (service != null && service.mIsEnableGetFrame) {
                sleepGetFrame();
                Image image = mImageReader.acquireLatestImage();
                if (image == null) {
                    drawFrame();
                    continue;
                }

                drawFrame(image);
                image.close();
            }

            mEncoder.signalEndOfInputStream();
        }

        private void sleepGetFrame() {
            try {
                Thread.sleep(mGetFrameDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Bitmap outputBitmap = null;
        private void drawFrame(Image image) {
            if (!mIsPrepareOpengl) return;
            Image.Plane plane = image.getPlanes()[0];

//            Log.e(TAG, "image w/h = " + image.getWidth() + " / " + image.getHeight());
//            Log.e(TAG, "image w2/h = " + plane.getRowStride() / plane.getPixelStride() + " / " + image.getHeight());

            if (outputBitmap == null) {
                outputBitmap = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(), image.getHeight(), Bitmap.Config.ARGB_8888);
            }
            outputBitmap.copyPixelsFromBuffer(plane.getBuffer());

            mCodecInputSurface.makeCurrent();
            mTexRenderer.updateImage(outputBitmap);
            mTexRenderer.renderResult(false);
            mCodecInputSurface.setPresentationTime(System.nanoTime());
            mCodecInputSurface.swapBuffers();
        }

        private void drawFrame() {
//            Log.e(TAG, "drawFrame mIsPrepareOpengl = " + mIsPrepareOpengl + ", time = " + ptsNs);
            if (!mIsPrepareOpengl || outputBitmap == null) return;

            mCodecInputSurface.makeCurrent();
            mTexRenderer.updateImage(outputBitmap);
            mTexRenderer.renderResult(false);
            mCodecInputSurface.setPresentationTime(System.nanoTime());
            mCodecInputSurface.swapBuffers();
        }

        private byte[] mBmpBytes = null;
        private ByteBuffer mBmpBuffer = null;

        private void drawFrame2(Image image) {
            if (!mIsPrepareOpengl) return;
            Image.Plane plane = image.getPlanes()[0];

            if (mBmpBytes == null) {
                mBmpBytes = new byte[image.getHeight() * image.getHeight() * 4];
                mBmpBuffer = ByteBuffer.wrap(mBmpBytes);
                Log.e(TAG, "create new bitmap buffer capacity = " + mBmpBuffer.capacity()
                        + ", limit = " + mBmpBuffer.limit()
                        + ", bytes.length = " + mBmpBytes.length);
            }

            Bitmap outputBitmap = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(), image.getHeight(), Bitmap.Config.ARGB_8888);
            outputBitmap.copyPixelsFromBuffer(plane.getBuffer());
            outputBitmap.copyPixelsToBuffer(mBmpBuffer);

            mCodecInputSurface.makeCurrent();
            mTexRenderer.updateImage(mBmpBuffer);
            mTexRenderer.renderResult(false);
            mCodecInputSurface.setPresentationTime(image.getTimestamp());
            mCodecInputSurface.swapBuffers();
        }

        private int mSaveImageCounter = 0;

        private void saveBitmap(Image image) {
            //test save 20 images
            if (mSaveImageCounter > 20) return;


            String file_path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()
                    + File.separator + "capture_" + mSaveImageCounter + ".png";

            try (FileOutputStream out = new FileOutputStream(file_path)) {
                Image.Plane plane = image.getPlanes()[0];
                Bitmap outputBitmap = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(), image.getHeight(), Bitmap.Config.ARGB_8888);
                outputBitmap.copyPixelsFromBuffer(plane.getBuffer());
                outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                outputBitmap.recycle();
//                Log.i(TAG, "image format: " + image.getFormat());
//                Log.i(TAG, "save image: " + file_path);
                mSaveImageCounter++;
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "save bitmap failed ..." + e);
            }
        }
    }

}