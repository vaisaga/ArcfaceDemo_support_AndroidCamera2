package com.arcsoft.arcfacedemo.activity;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.model.DrawInfo;
import com.arcsoft.arcfacedemo.util.ConfigUtil;
import com.arcsoft.arcfacedemo.util.DrawHelper;
import com.arcsoft.arcfacedemo.util.camera.Camera2Helper;
import com.arcsoft.arcfacedemo.util.camera.CameraHelper;
import com.arcsoft.arcfacedemo.util.camera.CameraListener;
import com.arcsoft.arcfacedemo.util.face.RecognizeColor;
import com.arcsoft.arcfacedemo.widget.FaceRectView;
import com.arcsoft.face.AgeInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.Face3DAngle;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.GenderInfo;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.VersionInfo;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.face.model.ArcSoftImageInfo;

import java.util.ArrayList;
import java.util.List;

public class FaceAttrPreviewActivity extends BaseActivity implements ViewTreeObserver.OnGlobalLayoutListener {
    private static final String TAG = "FaceAttrPreviewActivity";
    private CameraHelper cameraHelper;
    private Camera2Helper camera2Helper;
    private DrawHelper drawHelper;
    private Camera.Size previewSize;
    private Size mCamera2PreviewSize;
    private Integer rgbCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;//默认打开摄像头
    private FaceEngine faceEngine;
    private int afCode = -1;
    private int processMask = FaceEngine.ASF_AGE | FaceEngine.ASF_FACE3DANGLE | FaceEngine.ASF_GENDER | FaceEngine.ASF_LIVENESS;
    /**
     * 相机预览显示的控件，可为SurfaceView或TextureView
     */
    private View previewView;
    private FaceRectView faceRectView;

    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;
    /**
     * 所需的所有权限信息
     */
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_attr_preview);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().setAttributes(attributes);
        }

        // Activity启动后就锁定为启动时的方向
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        previewView = findViewById(R.id.texture_preview);
        faceRectView = findViewById(R.id.face_rect_view);
        //在布局结束后才做初始化操作
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(this);

    }

    private void initEngine() {
        faceEngine = new FaceEngine();
        afCode = faceEngine.init(this, DetectMode.ASF_DETECT_MODE_VIDEO, ConfigUtil.getFtOrient(this),
                16, 20, FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_AGE | FaceEngine.ASF_FACE3DANGLE | FaceEngine.ASF_GENDER | FaceEngine.ASF_LIVENESS);
        Log.i(TAG, "initEngine:  init: " + afCode);
        if (afCode != ErrorInfo.MOK) {
            showToast( getString(R.string.init_failed, afCode));
        }
    }

    private void unInitEngine() {

        if (afCode == 0) {
            afCode = faceEngine.unInit();
            Log.i(TAG, "unInitEngine: " + afCode);
        }
    }


    @Override
    protected void onDestroy() {
        if (cameraHelper != null) {
            cameraHelper.release();
            cameraHelper = null;
        }
        if(camera2Helper != null){
            camera2Helper.release();
            camera2Helper = null;
        }
        unInitEngine();
        super.onDestroy();
    }

    private void initCamera() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        CameraListener cameraListener = new CameraListener() {
            @Override
            public void onCameraOpened(Camera camera, int cameraId, int displayOrientation, boolean isMirror) {
                Log.i(TAG, "onCameraOpened: " + cameraId + "  " + displayOrientation + " " + isMirror);
                previewSize = camera.getParameters().getPreviewSize();
                drawHelper = new DrawHelper(previewSize.width, previewSize.height, previewView.getWidth(), previewView.getHeight(), displayOrientation
                        , cameraId, isMirror, false, false);
            }

            @Override
            public void onCamera2Opened(Size camera2PreviewSize, int cameraId, int displayOrientation, boolean isMirror) {
                mCamera2PreviewSize = new Size(camera2PreviewSize.getWidth(), camera2PreviewSize.getHeight());
                if(drawHelper == null){
                    drawHelper = new DrawHelper(camera2PreviewSize.getWidth(), camera2PreviewSize.getHeight(), previewView.getWidth(), previewView.getHeight(), displayOrientation
                            , cameraId, isMirror, false, false);
                }
            }

            //将相机传来的原始数据传入虹软算法
            @Override
            public void onPreview(byte[] nv21, Camera camera) {

                Log.d(TAG, "onPreview: nv21 size: " + nv21.length);

                if (faceRectView != null) {
                    faceRectView.clearFaceInfo();
                }
                List<FaceInfo> faceInfoList = new ArrayList<>();
//                long start = System.currentTimeMillis();
                int code = 0;
                if(cameraHelper != null){
                    code = faceEngine.detectFaces(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList);
                }else{
                    code = faceEngine.detectFaces(nv21, mCamera2PreviewSize.getWidth(), mCamera2PreviewSize.getHeight(), FaceEngine.CP_PAF_NV21, faceInfoList);
                }
                if (code == ErrorInfo.MOK && faceInfoList.size() > 0) {
                    if(cameraHelper != null){
                        code = faceEngine.process(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList, processMask);
                    }else{
                        code = faceEngine.process(nv21, mCamera2PreviewSize.getWidth(), mCamera2PreviewSize.getHeight(), FaceEngine.CP_PAF_NV21, faceInfoList, processMask);
                    }
                    if (code != ErrorInfo.MOK) {
                        return;
                    }
                } else {
                    return;
                }

                List<AgeInfo> ageInfoList = new ArrayList<>();
                List<GenderInfo> genderInfoList = new ArrayList<>();
                List<Face3DAngle> face3DAngleList = new ArrayList<>();
                List<LivenessInfo> faceLivenessInfoList = new ArrayList<>();
                int ageCode = faceEngine.getAge(ageInfoList);
                int genderCode = faceEngine.getGender(genderInfoList);
                int face3DAngleCode = faceEngine.getFace3DAngle(face3DAngleList);
                int livenessCode = faceEngine.getLiveness(faceLivenessInfoList);

                // 有其中一个的错误码不为ErrorInfo.MOK，return
                if ((ageCode | genderCode | face3DAngleCode | livenessCode) != ErrorInfo.MOK) {
                    return;
                }
                if (faceRectView != null && drawHelper != null) {
                    List<DrawInfo> drawInfoList = new ArrayList<>();
                    for (int i = 0; i < faceInfoList.size(); i++) {
                        drawInfoList.add(new DrawInfo(drawHelper.adjustRect(faceInfoList.get(i).getRect()), genderInfoList.get(i).getGender(), ageInfoList.get(i).getAge(), faceLivenessInfoList.get(i).getLiveness(), RecognizeColor.COLOR_UNKNOWN, null));
                    }
                    drawHelper.draw(faceRectView, drawInfoList);
                }
            }

            @Override
            public void onCameraClosed() {
                Log.i(TAG, "onCameraClosed: ");
            }

            @Override
            public void onCameraError(Exception e) {
                Log.i(TAG, "onCameraError: " + e.getMessage());
            }

            @Override
            public void onCameraConfigurationChanged(int cameraID, int displayOrientation) {
                if (drawHelper != null) {
                    drawHelper.setCameraDisplayOrientation(displayOrientation);
                }
                Log.i(TAG, "onCameraConfigurationChanged: " + cameraID + "  " + displayOrientation);
            }
        };
        //按顺序执行方法
        Log.d(TAG, "initCamera: screen width = "+previewView.getMeasuredWidth()+", height = "+previewView.getMeasuredHeight());
//        cameraHelper = new CameraHelper.Builder()
//                .previewViewSize(new Point(previewView.getMeasuredWidth(), previewView.getMeasuredHeight()))//屏幕的长宽
//                .rotation(getWindowManager().getDefaultDisplay().getRotation())//屏幕旋转的方向
//                .specificCameraId(rgbCameraId != null ? rgbCameraId : Camera.CameraInfo.CAMERA_FACING_FRONT)//默认开启前置摄像头
//                .isMirror(false)//是否镜像
//                .previewOn(previewView)//设置预览控件
//                .cameraListener(cameraListener)//传入监听对象，可以通过监听获取NV21数据
//                .build();//将上面方法传进去的参数做进一步初始化
//        cameraHelper.init();
//        cameraHelper.start();//启动相机
        camera2Helper = new Camera2Helper.Builder()
                .previewViewSize(new Point(previewView.getMeasuredWidth(), previewView.getMeasuredHeight()))//屏幕的长宽
                .rotation(getWindowManager().getDefaultDisplay().getRotation())//屏幕旋转的方向
                .specificCameraId(rgbCameraId != null ? rgbCameraId : Camera.CameraInfo.CAMERA_FACING_FRONT)//默认开启前置摄像头
                .isMirror(false)//是否镜像
                .previewOn(previewView)//设置预览控件
                .cameraListener(cameraListener)//传入监听对象，可以通过监听获取NV21数据
                .activityBelong(this)
                .build();//将上面方法传进去的参数做进一步初始化
        camera2Helper.init();
        camera2Helper.start();
    }

    @Override
    void afterRequestPermission(int requestCode, boolean isAllGranted) {
        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            if (isAllGranted) {
                initEngine();
                initCamera();
            } else {
                showToast(getString( R.string.permission_denied));
            }
        }
    }

    /**
     * 在{@link #previewView}第一次布局完成后，去除该监听，并且进行引擎和相机的初始化
     */
    @Override
    public void onGlobalLayout() {
        previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initEngine();
            initCamera();
        }
    }


    /**
     * 切换相机。注意：若切换相机发现检测不到人脸，则极有可能是检测角度导致的，需要销毁引擎重新创建或者在设置界面修改配置的检测角度
     *
     * @param view
     */
    public void switchCamera(View view) {
//        if (cameraHelper != null) {
//            boolean success = cameraHelper.switchCamera();//切换相机
//            if (!success) {
//                showToast(getString(R.string.switch_camera_failed));
//            } else {
//                showLongToast(getString(R.string.notice_change_detect_degree));
//            }
//        }
        if (camera2Helper != null) {
            boolean success = camera2Helper.switchCamera();//切换相机
            if (!success) {
                showToast(getString(R.string.switch_camera_failed));
            } else {
                showLongToast(getString(R.string.notice_change_detect_degree));
            }
        }
    }
}
