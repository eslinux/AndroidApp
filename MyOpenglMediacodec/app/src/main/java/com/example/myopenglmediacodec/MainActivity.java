package com.example.myopenglmediacodec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    //permission
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_PROJECTION = 2;

    private final String [] mPermissions =  new String[] {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
    };

    private Intent mProjData;

    //UI component
    private Button mStartButton;
    private boolean mIsStarted = false;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        reqPermission();

        mStartButton = findViewById(R.id.btnStartRecord);
        mStartButton.setOnClickListener(v -> {
            if (mIsStarted){
                stopScreenCapture();
                mStartButton.setText("start");
                mIsStarted = false;
            }else{
                startScreenCapture();
                mStartButton.setText("stop");
                mIsStarted = true;
            }
        });

    }

    private void reqPermission() {
        if (ActivityCompat.checkSelfPermission(this, mPermissions[0]) == PackageManager.PERMISSION_DENIED){
            ActivityCompat.requestPermissions(this, mPermissions, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION){
            if (grantResults[0] == PackageManager.PERMISSION_DENIED){
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (REQ_PROJECTION == requestCode){
            if (resultCode == RESULT_OK){
                mProjData = data;
                startScreenCapture();
            }
        }
    }

    private void startScreenCapture(){
        if (mProjData == null){
            Intent captureIntent = ((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).createScreenCaptureIntent();
            startActivityForResult(captureIntent, REQ_PROJECTION);
            return;
        }

        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.setAction("start");
        serviceIntent.putExtra("com.ns.pData", mProjData);
        startService(serviceIntent);
    }
    private void stopScreenCapture(){
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.setAction("stop");
        startService(serviceIntent);
    }
}