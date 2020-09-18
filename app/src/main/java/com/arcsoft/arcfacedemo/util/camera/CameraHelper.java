package com.arcsoft.arcfacedemo.util.camera;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 打开单个相机辅助类，和{@link CameraListener}共同使用，获取nv21数据等操作
 */
public class CameraHelper implements Camera.PreviewCallback {
    private static final String TAG = "CameraHelper";
    private Camera mCamera;//camera对象
    private int mCameraId;//camera id
    private Point previewViewSize;//屏幕的长宽，在选择最佳相机比例时用到
    private View previewDisplayView;//用于预览的view对象，可以是TextureView也可以是SurfaceView
    private Camera.Size previewSize;//预览大小
    private Point specificPreviewSize;//用户指定的预览大小
    private int displayOrientation = 0;//预览方向
    private int rotation;
    private int additionalRotation;
    private boolean isMirror = false;//是否镜像

    private Integer specificCameraId = null;//用户可以指定相机的id，使用android定义的id
                                            //这里之所以用Integer不用int，是因为可以达到检测系统有没有相机的作用
    private CameraListener cameraListener;//监听，在适当的时候调用方法，通知用户

    //构造方法
    private CameraHelper(CameraHelper.Builder builder) {
        previewDisplayView = builder.previewDisplayView;
        specificCameraId = builder.specificCameraId;
        cameraListener = builder.cameraListener;
        rotation = builder.rotation;
        additionalRotation = builder.additionalRotation;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.previewSize;
        if (builder.previewDisplayView instanceof TextureView) {
            isMirror = builder.isMirror;
        } else if (isMirror) {
            throw new RuntimeException("mirror is effective only when the preview is on a textureView");
        }
    }

    // 播放视频流需要一个surface来接收视频数据。
    // 这个surface可以是SurfaceView，也可以是textureView对应surface。
    public void init() {
        //根据View类型设置监听
        if (previewDisplayView instanceof TextureView) {
            //TextureView标准写法，设置listener
            ((TextureView) this.previewDisplayView).setSurfaceTextureListener(textureListener);
        } else if (previewDisplayView instanceof SurfaceView) {
            //SurfaceView标准写法，设置回调
            ((SurfaceView) previewDisplayView).getHolder().addCallback(surfaceCallback);
        }

        if (isMirror) {
            previewDisplayView.setScaleX(-1);//先做水平镜像，然后再放大1倍
        }
    }

    //启动相机的方法封装
    //包括了设置相机参数、设置预览、启动预览
    public void start() {
        synchronized (this) {
            if (mCamera != null) {
                return;
            }
            //相机数量为2则打开1,1则打开0,相机ID 1为前置，0为后置
            mCameraId = Camera.getNumberOfCameras() - 1;//获取相机数量
            //若指定了相机ID且该相机存在，则打开指定的相机
            if (specificCameraId != null && specificCameraId <= mCameraId) {
                mCameraId = specificCameraId;
            }

            //没有相机
            if (mCameraId == -1) {
                if (cameraListener != null) {
                    cameraListener.onCameraError(new Exception("camera not found"));//抛出异常
                }
                return;
            }
            if (mCamera == null) {
                mCamera = Camera.open(mCameraId);//打开相机
            }

            displayOrientation = getCameraOri(rotation);//获得显示角度
            mCamera.setDisplayOrientation(displayOrientation);//设置显示角度
            //配置相机参数
            try {
                Camera.Parameters parameters = mCamera.getParameters();//获得相机当前设置参数
                parameters.setPreviewFormat(ImageFormat.NV21);//设置预览格式为NV21

                //预览大小设置
                previewSize = parameters.getPreviewSize();//获得当前预览设置大小
                List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();//获得相机支持的预览大小
                if (supportedPreviewSizes != null && supportedPreviewSizes.size() > 0) {
                    //根据相机支持的预览大小和当前准备设置的预览大小(或者说是屏幕大小)，选择合适的预览大小设置相机
                    previewSize = getBestSupportedSize(supportedPreviewSizes, previewViewSize);
                }
                Log.d(TAG, "start: actual previewSize.width1 = " + previewSize.width +
                        ", previewSize.height1 = " + previewSize.height);
                parameters.setPreviewSize(previewSize.width, previewSize.height);//重新设置预览的大小
                //测试用，自定预览大小
//                parameters.setPreviewSize(1280, 720);//重新设置预览的大小
                //再次获取预览大小，用于查看设置情况
                Camera.Size previewSizeTemp = parameters.getPreviewSize();
                Log.d(TAG, "start: actual previewSize.width2 = " + previewSize.width +
                        ", previewSize.height2 = " + previewSize.height);

                //对焦模式设置
                List<String> supportedFocusModes = parameters.getSupportedFocusModes();//获取支持的对焦模式
                if (supportedFocusModes != null && supportedFocusModes.size() > 0) {
                    //如果支持用于拍照的连续自动对焦，就用拍照连续自动对焦模式
                    if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                        //否则如果支持用于录制的连续自动对焦，就用录制的连续自动对焦模式
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        //否则就使用普通自动对焦模式
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                }
                mCamera.setParameters(parameters);//重新设置相机参数

                //测试设置情况
//                Camera.Parameters parametersAfter = mCamera.getParameters();//获得相机当前设置参数
//                Camera.Size previewSizeTemp2 = parametersAfter.getPreviewSize();

                //标准写法，设置相机预览对象
                if (previewDisplayView instanceof TextureView) {
                    mCamera.setPreviewTexture(((TextureView) previewDisplayView).getSurfaceTexture());
                } else {
                    mCamera.setPreviewDisplay(((SurfaceView) previewDisplayView).getHolder());
                }
                mCamera.setPreviewCallback(this);//标准写法
                mCamera.startPreview();//标准写法，启动预览
                if (cameraListener != null) {
                    //通知用户打开的相机信息
                    cameraListener.onCameraOpened(mCamera, mCameraId, displayOrientation, isMirror);
                }
            } catch (Exception e) {
                if (cameraListener != null) {
                    cameraListener.onCameraError(e);
                }
            }
        }
    }

    //根据用户传入的屏幕旋转角度，得到摄像头旋转角度
    private int getCameraOri(int rotation) {
        int degrees = rotation * 90;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        additionalRotation /= 90;
        additionalRotation *= 90;//补正角度
        degrees += additionalRotation;//加上额外角度
        int result;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);//获取摄像机信息，角度，前后置等
        //根据摄像头是前置还是后置，补正角度信息
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    //停止相机，标准写法顺序
    public void stop() {
        synchronized (this) {
            if (mCamera == null) {
                return;
            }
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            if (cameraListener != null) {
                cameraListener.onCameraClosed();//通知用户相机关闭
            }
        }
    }

    //判断相机状态
    public boolean isStopped() {
        synchronized (this) {
            return mCamera == null;
        }
    }

    //释放相机资源
    public void release() {
        synchronized (this) {
            stop();
            previewDisplayView = null;
            specificCameraId = null;
            cameraListener = null;
            previewViewSize = null;
            specificPreviewSize = null;
            previewSize = null;
        }
    }

    //根据相机支持的预览大小和当前准备设置的预览大小(或者说是屏幕大小)，选择合适的预览大小设置相机
    //基本思想是，返回的预览长宽比尽量接近用户要求的预览长宽比，并从这些接近的长宽比中，选择最高的分辨率
    private Camera.Size getBestSupportedSize(List<Camera.Size> sizes, Point previewViewSize) {
        if (sizes == null || sizes.size() == 0) {
            return mCamera.getParameters().getPreviewSize();//返回默认的预览大小
        }
        List<Camera.Size> sizesBefore = sizes;
        Camera.Size[] tempSizes = sizes.toArray(new Camera.Size[0]);//集合转数组，使用toArray第二种重载方法，不用一个个元素去初始化
                                                                    //不知道为啥这里数组大小用了0
        //要实现减序排序，得通过包装类型数组，基本类型数组是不行的
        Arrays.sort(tempSizes, new Comparator<Camera.Size>() {
            @Override
            /*
             * 此处与c++的比较函数构成不一致
             * c++返回bool型,而Java返回的为int型
             * 当返回值>0时
             * 进行交换，即排序(源码实现为两枢轴快速排序)
             */
            public int compare(Camera.Size o1, Camera.Size o2) {
                if (o1.width > o2.width) {
                    return -1;
                } else if (o1.width == o2.width) {
                    return o1.height > o2.height ? -1 : 1;//如果宽度相等，就比较高度
                } else {
                    return 1;
                }
            }
        });
        sizes = Arrays.asList(tempSizes);//排序后又从数组转为集合

        Camera.Size bestSize = sizes.get(0);//上面排序就是为了这一步获取最大预览大小(最大分辨率)
        float previewViewRatio;//预览长宽比
        //计算长宽比
        if (previewViewSize != null) {
            //计算用户需求的预览长宽比
            previewViewRatio = (float) previewViewSize.x / (float) previewViewSize.y;
        } else {
            //如果用户没有传入想要设置的预览大小，就用相机支持的最高分辨率的长宽比
            previewViewRatio = (float) bestSize.width / (float) bestSize.height;
        }

        //根据长宽比调整预览角度，按竖屏显示调整
        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }
        boolean isNormalRotate = (additionalRotation % 180 == 0);

        //遍历集合
        for (Camera.Size s : sizes) {
            //如果用户指定了预览大小，找到符合的预览大小
            if (specificPreviewSize != null && specificPreviewSize.x == s.width && specificPreviewSize.y == s.height) {
                return s;
            }
            //基本思想是，返回的预览长宽比尽量接近用户要求的长宽比，并从这些接近的长宽比中，选择最高的分辨率
            //还有一点要注意的是，遍历的这个集合，在上面已经经过了降序排序了
            if (isNormalRotate) {
                if (Math.abs((s.height / (float) s.width) - previewViewRatio) < Math.abs(bestSize.height / (float) bestSize.width - previewViewRatio)) {
                    bestSize = s;
                }
            } else {
                if (Math.abs((s.width / (float) s.height) - previewViewRatio) < Math.abs(bestSize.width / (float) bestSize.height - previewViewRatio)) {
                    bestSize = s;
                }
            }
        }
        //bestSize.width = 1280;
        //bestSize.height = 720;
        return bestSize;
    }

    //获取相机支持的预览大小集合
    public List<Camera.Size> getSupportedPreviewSizes() {
        if (mCamera == null) {
            return null;
        }
        return mCamera.getParameters().getSupportedPreviewSizes();
    }

    //获取相机支持的照片大小
    public List<Camera.Size> getSupportedPictureSizes() {
        if (mCamera == null) {
            return null;
        }
        return mCamera.getParameters().getSupportedPictureSizes();
    }

    //这里是获取到的NV21数据回调
    @Override
    public void onPreviewFrame(byte[] nv21, Camera camera) {
        if (cameraListener != null) {
            cameraListener.onPreview(nv21, camera);
        }
    }

    // TextureView标准写法，重写下面的方法
    // 唯一要做的就是获取用于渲染内容的SurfaceTexture
    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        // 在调用TextureView的draw方法时，如果还没有初始化SurfaceTexture。那么就会初始化它。
        // 初始化好时，就会回调这个接口。SurfaceTexture初始化好时，
        // 就表示可以接收外界的绘制指令了（可以异步接收）。
        // 然后SurfaceTexture会以GL纹理信息更新到TextureView对应的HardwareLayer中。然后就会在HardwareLayer中显示。
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
//            start();
            if (mCamera != null) {
                try {
                    mCamera.setPreviewTexture(surfaceTexture);//先设置预览对象
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged: " + width + "  " + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            stop();
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };
    //标准写法
    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
//            start();
            if (mCamera != null) {
                try {
                    mCamera.setPreviewDisplay(holder);//这里先设置了预览对象
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stop();
        }
    };

    //改变显示角度
    public void changeDisplayOrientation(int rotation) {
        if (mCamera != null) {
            this.rotation = rotation;
            displayOrientation = getCameraOri(rotation);
            mCamera.setDisplayOrientation(displayOrientation);
            if (cameraListener != null) {
                cameraListener.onCameraConfigurationChanged(mCameraId, displayOrientation);
            }
        }
    }

    //切换相机
    public boolean switchCamera() {
        if (Camera.getNumberOfCameras() < 2) {
            return false;
        }
        // cameraId ,0为后置，1为前置
        specificCameraId = 1 - mCameraId;
        stop();
        start();
        return true;
    }

    public static final class Builder {

        /**
         * 预览显示的view，目前仅支持surfaceView和textureView
         */
        private View previewDisplayView;

        /**
         * 是否镜像显示，只支持textureView
         */
        private boolean isMirror;
        /**
         * 用户可以指定的相机ID
         * 使用android定义的id
         * 比如Camera.CameraInfo.CAMERA_FACING_BACK
         */
        private Integer specificCameraId;
        /**
         * 事件回调
         */
        private CameraListener cameraListener;
        /**
         * 屏幕的长宽，在选择最佳相机比例时用到
         */
        private Point previewViewSize;
        /**
         * 传入getWindowManager().getDefaultDisplay().getRotation()的值即可
         * 即传入屏幕旋转的方向
         */
        private int rotation;
        /**
         * 指定的预览宽高，若系统支持则会以这个预览宽高进行预览
         */
        private Point previewSize;

        /**
         * 额外的旋转角度（用于适配一些定制设备）
         * 注意额外角度最终会补正到90度倍数
         */
        private int additionalRotation;

        public Builder() {
        }


        public Builder previewOn(View val) {
            //判断是不是SurfaceView或者TextureView实例
            if (val instanceof SurfaceView || val instanceof TextureView) {
                previewDisplayView = val;
                return this;
            } else {
                throw new RuntimeException("you must preview on a textureView or a surfaceView");
            }
        }


        public Builder isMirror(boolean val) {
            isMirror = val;
            return this;
        }

        public Builder previewSize(Point val) {
            previewSize = val;
            return this;
        }

        //设置预览大小
        public Builder previewViewSize(Point val) {
            previewViewSize = val;
            return this;
        }

        public Builder rotation(int val) {
            rotation = val;
            return this;
        }

        public Builder additionalRotation(int val) {
            additionalRotation = val;
            return this;
        }

        public Builder specificCameraId(Integer val) {
            specificCameraId = val;
            return this;
        }

        public Builder cameraListener(CameraListener val) {
            cameraListener = val;
            return this;
        }

        //创建CameraHelper实例
        public CameraHelper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default previewSize");
            }
            if (cameraListener == null) {
                Log.e(TAG, "cameraListener is null, callback will not be called");
            }
            if (previewDisplayView == null) {
                throw new RuntimeException("you must preview on a textureView or a surfaceView");
            }
            return new CameraHelper(this);
        }
    }

}
