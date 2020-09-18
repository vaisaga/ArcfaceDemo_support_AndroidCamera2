package com.arcsoft.arcfacedemo.util.camera;

import android.hardware.Camera;
import android.util.Size;


public interface CameraListener {
    /**
     * 当打开时执行，用于通知用户打开的相机信息
     * @param camera 相机实例
     * @param cameraId 相机ID
     * @param displayOrientation 相机预览旋转角度
     * @param isMirror 是否镜像显示
     */
    void onCameraOpened(Camera camera, int cameraId, int displayOrientation, boolean isMirror);

    /**
     * 当camera2打开时执行，用于通知用户打开的相机信息
     * @param previewSize camera2预览大小
     * @param cameraId 相机ID
     * @param displayOrientation 相机预览旋转角度
     * @param isMirror 是否镜像显示
     */
    void onCamera2Opened(Size camera2PreviewSize, int cameraId, int displayOrientation, boolean isMirror);

    /**
     * 预览数据回调，用于将图像信息传递给用户
     * @param data 预览数据
     * @param camera 相机实例
     */
    void onPreview(byte[] data, Camera camera);

    /**
     * 当相机关闭时执行，通知用户相机关闭了
     */
    void onCameraClosed();

    /**
     * 当出现异常时执行，通知用户相机执行异常，用户可以查看具体的异常信息
     * @param e 相机相关异常
     */
    void onCameraError(Exception e);

    /**
     * 属性变化时调用，通知用户相机的id和旋转方向发生了什么变化
     * @param cameraID  相机ID
     * @param displayOrientation    相机旋转方向
     */
    void onCameraConfigurationChanged(int cameraID, int displayOrientation);
}
