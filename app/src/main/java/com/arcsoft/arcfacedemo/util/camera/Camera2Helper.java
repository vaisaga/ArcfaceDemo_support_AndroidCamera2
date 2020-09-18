package com.arcsoft.arcfacedemo.util.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ProjectName: ArcfaceDemo
 * @Package: com.arcsoft.arcfacedemo.util.camera
 * @ClassName: Camera2Helper
 * @Description: 给予camera2打开单个相机辅助类，和{@link CameraListener}共同使用，获取nv21数据等操作
 * @Author: lyp
 * @CreateDate: 2020/9/14 17:27
 * @UpdateUser: lyp
 * @UpdateDate: 2020/9/14 17:27
 * @UpdateRemark:
 * @Version: 1.0
 */
public class Camera2Helper implements Camera.PreviewCallback {
    private static final String TAG = "Camera2Helper";

    /**
     * A reference to the open {@link CameraDevice}.
     * 对打开的{@link CameraDevice}的引用。
     */
    private CameraDevice mCameraDevice;

    private String mCameraId;//camera id
    private Point previewViewSize;//屏幕的长宽，在选择最佳相机比例时用到
    private View previewDisplayView;//用于预览的view对象，可以是TextureView也可以是SurfaceView
    /**
     * An {@link AutoFitTextureView} for camera preview.
     * {@link AutoFitTextureView}用于摄像机预览。
     */
    private TextureView mTextureView;

    private SurfaceView mSurfaceView;//对surfaceView支持还没完善，先避免用SurfaceView

    private Activity mActivity;

    private static final int REQUEST_CAMERA_PERMISSIONS = 1;

    /**
     * The {@link Size} of camera preview.
     * 相机预览的{@link Size}尺寸。
     */
    private Size mPreviewSize;
    private Size mCameraPreviewSize;

    private Point specificPreviewSize;//用户指定的预览大小
    private int displayOrientation = 0;//预览方向
    private int rotation;
    private int additionalRotation;
    private boolean isMirror = false;//是否镜像

    private Integer specificCameraId = null;//用户可以指定相机的id，使用android定义的id
    //这里之所以用Integer不用int，是因为可以达到检测系统有没有相机的作用
    private CameraListener cameraListener;//监听，在适当的时候调用方法，通知用户

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     * {@link CaptureRequest.Builder}用于摄像机预览
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * Tolerance when comparing aspect ratios.
     * 比较宽高比时的公差。
     */
    private static final double ASPECT_RATIO_TOLERANCE = 0.005;

    /**
     * Max preview width that is guaranteed by Camera2 API
     * Camera2 API保证的最大预览宽度
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     * Camera2 API保证的最大预览高度
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * Conversion from screen rotation to JPEG orientation.
     * 从屏幕旋转到JPEG方向的转换。
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    /* 用于选择是否根据摄像头预览尺寸，调整textureView尺寸*/
    private boolean mSetAspectRatio;

    /**
     * An additional thread for running tasks that shouldn't block the UI.  This is used for all
     * callbacks from the {@link CameraDevice} and {@link CameraCaptureSession}s.
     * 用于运行不应阻塞UI的任务的附加线程。
     * 这用于来自{@link CameraDevice}和{@link CameraCaptureSession}的所有回调。
     * android多线程：https://www.jianshu.com/p/9c10beaa1c95
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     * {@link处理程序}，用于在后台运行任务。
     */
    private Handler mBackgroundHandler;

    /**
     * Camera state: Device is closed.
     * 相机状态：设备已关闭。
     */
    private static final int STATE_CLOSED = 0;

    /**
     * Camera state: Device is opened, but is not capturing.
     * 相机状态：设备已打开，但未捕获。
     */
    private static final int STATE_OPENED = 1;

    /**
     * Camera state: Showing camera preview.
     * 相机状态：显示相机预览。
     */
    private static final int STATE_PREVIEW = 2;

    /**
     * Camera state: Waiting for 3A convergence before capturing a photo.
     * 等待3A收敛后再拍摄照片。
     */
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;

    //用于接收预览数据中的nv21格式
    private RefCountedAutoCloseable<ImageReader> mNV21ImageReader;

    /**
     * The state of the camera device.
     * 摄像头设备的状态。
     *
     * @see #mPreCaptureCallback
     */
    private int mState = STATE_CLOSED;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     * 摄像头捕获会话
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * The {@link CameraCharacteristics} for the currently configured camera device.
     * 当前配置的相机设备的{@link CameraCharacteristics}。
     */
    private CameraCharacteristics mCharacteristics;

    /**
     * An {@link OrientationEventListener} used to determine when device rotation has occurred.
     * This is mainly necessary for when the device is rotated by 180 degrees, in which case
     * onCreate or onConfigurationChanged is not called as the view dimensions remain the same,
     * but the orientation of the has changed, and thus the preview rotation must be updated.
     *
     * {@link OrientationEventListener}用于确定何时发生设备旋转。
     * 当设备旋转180度时，这主要是必需的，在这种情况下，由于视图尺寸保持不变，
     * 而不会调用onCreate或onConfigurationChanged，但是视图的方向已更改，因此必须更新预览旋转。
     */
    private OrientationEventListener mOrientationListener;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     * 同步关键类，构造方法传入的数字是多少，则同一个时刻，只运行多少个进程同时运行制定代码
     * https://www.cnblogs.com/klbc/p/9500947.html
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether or not the currently configured camera device is fixed-focus.
     * 当前配置的相机设备是否为固定焦点。
     */
    private boolean mNoAFRun = false;

    /**
     * A lock protecting camera state.
     * 用于同步
     */
    private final Object mCameraStateLock = new Object();
    private ActivityCompat.PermissionCompatDelegate FragmentCompat;


    //构造方法
    public Camera2Helper(Camera2Helper.Builder builder) {
        previewDisplayView = builder.previewDisplayView;
        specificCameraId = builder.specificCameraId;
        cameraListener = builder.cameraListener;
        rotation = builder.rotation;
        additionalRotation = builder.additionalRotation;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.previewSize;
        mActivity = builder.activity;
        if (builder.previewDisplayView instanceof TextureView) {
            isMirror = builder.isMirror;
        } else if (isMirror) {
            throw new RuntimeException("mirror is effective only when the preview is on a textureView");
        }
    }

    // 预览需要一个surface来接收数据。
    // 这个surface可以是SurfaceView，也可以是textureView对应surface。
    public void init() {
        //根据View类型设置监听
        if (previewDisplayView instanceof TextureView) {
            //TextureView标准写法，设置listener
            ((TextureView) this.previewDisplayView).setSurfaceTextureListener(textureListener);
            mTextureView = (TextureView) this.previewDisplayView;
        } else if (previewDisplayView instanceof SurfaceView) {
            //SurfaceView标准写法，设置回调
            ((SurfaceView) previewDisplayView).getHolder().addCallback(surfaceCallback);
            mSurfaceView = (SurfaceView) previewDisplayView;
        }

        if (isMirror) {
            previewDisplayView.setScaleX(-1);//先做水平镜像，然后再放大1倍
        }

        mSetAspectRatio = true;

        // Setup a new OrientationEventListener.  This is used to handle rotation events like a
        // 180 degree rotation that do not normally trigger a call to onCreate to do view re-layout
        // or otherwise cause the preview TextureView's size to change.
        // 屏幕角度旋转监听
        // 因为没有传上下文，所以先不移植
//        mOrientationListener = new OrientationEventListener(getActivity(),
//                SensorManager.SENSOR_DELAY_NORMAL) {
//            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
//            @Override
//            public void onOrientationChanged(int orientation) {
//                if (mTextureView != null && mTextureView.isAvailable()) {
//                    configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
//                }
//            }
//        };
    }

    /**
     * A {@link Handler} for showing {@link Toast}s on the UI thread.
     * 一个{@link Handler}，用于在UI线程上显示{@link Toast}。
     */
    private final Handler mMessageHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Activity activity = mActivity;
            if (activity != null) {
                Toast.makeText(activity, (String) msg.obj, Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * Starts a background thread and its {@link Handler}.
     * 启动后台线程及其{@link Handler}。
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        synchronized (mCameraStateLock) {
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private final ImageReader.OnImageAvailableListener mOnNV21ImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            //抛出nv21数据
            Image image;
            try {
//                image = reader.acquireNextImage();//取出image
                image = reader.acquireLatestImage();//google推荐使用此方法来处理视频流

                int width = image.getWidth();
                int heigth = image.getHeight();
                // size是宽乘高的1.5倍 可以通过ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)得到
                int i420Size = width * heigth * 3 / 2;

                //准备存储nv21的数组，nv21存储格式为，y一个plane，uv交错为一个plane，v在前u在后
                byte[] nv21 = new byte[i420Size];
                byte[] i420 = new byte[i420Size];

                Image.Plane[] planes = image.getPlanes();
                //remaining0 = rowStride*(heigth-1)+with Y分量byte数组的size
                int remaining0 = planes[0].getBuffer().remaining();
                int capacity0 = planes[0].getBuffer().capacity();
                //remaining1 = rowStride*(heigth/2-1)+with-1 U分量byte数组的size
                //一般pixelStride=2的时候(UV交错排列)会出现少一个字节的情况
                int remaining1 = planes[1].getBuffer().remaining();
                int capacity1 = planes[1].getBuffer().capacity();
                //remaining2 = rowStride*(heigth/2-1)+with-1 V分量byte数组的size
                //一般pixelStride=2的时候(UV交错排列)会出现少一个字节的情况
                int remaining2 = planes[2].getBuffer().remaining();
                int capacity2 = planes[2].getBuffer().capacity();

                //获取pixelStride，一般是判断UV分量是否交错存储
                int pixelStride0 = planes[0].getPixelStride();//y分量正常都是1
                //如果uv交错，pixelstride=2，如果uv分成两个plane，pixelstirde=1
                int pixelStride1 = planes[1].getPixelStride();
                int pixelStride2 = planes[2].getPixelStride();

                //获取RowStride，可能跟width相等，可能不相等
                int rowOffest0 = planes[0].getRowStride();
                int rowOffest1 = planes[1].getRowStride();
                int rowOffest2 = planes[2].getRowStride();

                //分别准备三个数组接收YUV分量。
                byte[] yRawSrcBytes = new byte[remaining0];
                //如果是uv交错的话，uRawSrcBytes和vRawSrcBytes里面的uv排列是相反的
                //uRawSrcBytes里面的u在前，v在后
                //vRawSrcBytes里面的v在前，u在后
                byte[] uRawSrcBytes = new byte[remaining1];
                byte[] vRawSrcBytes = new byte[remaining2];
                //如果uv交错的话，实际上uRawSrcBytes和vRawSrcBytes里面的数据是一样的
                planes[0].getBuffer().get(yRawSrcBytes);
                planes[1].getBuffer().get(uRawSrcBytes);
                planes[2].getBuffer().get(vRawSrcBytes);

                if (rowOffest0 == width) {
                    //两者相等，说明Y块紧密相连，可以直接拷贝Y
                    //判断uv是否交错,交错用nv12，不交错用I420
                    if(pixelStride1 == 2){
                        //直接拷贝Y
                        System.arraycopy(yRawSrcBytes, 0, nv21, 0, rowOffest0 * heigth);
                        //uv交错，直接拷贝，拷贝vRawSrcBytes得到nv21，拷贝uRawSrcBytes得到nv12
                        System.arraycopy(vRawSrcBytes, 0, nv21, rowOffest0 * heigth, rowOffest0*(heigth/2-1)+width-1);
                    }else{
                        System.arraycopy(yRawSrcBytes, 0, i420, 0, rowOffest0 * heigth);
                        //uv分开，u和v单独拷贝，生成I420 YYYYYYYY UU VV
                        System.arraycopy(uRawSrcBytes, 0, i420, rowOffest0 * heigth, rowOffest1*(heigth/2-1)+width-1);
                        System.arraycopy(vRawSrcBytes, 0, i420, rowOffest0 * heigth + rowOffest1*(heigth/2-1)+width-1, rowOffest2*(heigth/2-1)+width-1);
                        //I420转NV21
                        ImageUtil.I420ToNV21(nv21, i420, width, heigth); }
                }else{
                    //根据每个分量的size先生成byte数组
                    byte[] ySrcBytes = new byte[width * heigth];
                    byte[] uSrcBytes = new byte[width * heigth / 2 - 1];
                    byte[] vSrcBytes = new byte[width * heigth / 2 - 1];
                    //判断uv是否交错,交错用nv21，不交错用I420
                    if(pixelStride1 == 2){
                        //uv交错
                        for (int row = 0; row < heigth; row++) {
                            //源数组每隔 rowOffest 个bytes 拷贝 width 个bytes到目标数组
                            System.arraycopy(yRawSrcBytes, rowOffest0 * row, ySrcBytes, width * row, width);
                            //y执行两次，uv执行一次
                            if (row % 2 == 0) {
                                //最后一行需要减一，拷贝vRawSrcBytes得到nv21
                                if (row == heigth - 2) {
                                    System.arraycopy(vRawSrcBytes, rowOffest2 * row / 2, vSrcBytes, width * row / 2, width - 1);
                                } else {
                                    System.arraycopy(vRawSrcBytes, rowOffest2 * row / 2, vSrcBytes, width * row / 2, width);
                                }
                            }
                        }
                        //yuv拷贝到一个数组里面
                        System.arraycopy(ySrcBytes, 0, nv21, 0, width * heigth);
                        System.arraycopy(vSrcBytes, 0, nv21, width * heigth, width * heigth / 2 - 1);
                    }else{
                        //uv分开，待完善
                        Log.e(TAG, "run: do something");
                    }
                }
                Log.d(TAG, "onImageAvailable: nv21 size: " + i420Size);
                if (cameraListener != null && mPreviewSize != null) {
                    //为了兼容，这里传null
                    cameraListener.onPreview(nv21, null);
                }
                image.close();
            } catch (IllegalStateException e) {
//                Log.e(TAG, "onImageAvailable: " + e);
                if (cameraListener != null) {
                    cameraListener.onCameraError(e);
                }
                return;
            }
        }

    };

    /**
     * Sets up state related to camera that is needed before opening a {@link CameraDevice}.
     * 设置与打开{@link CameraDevice}之前所需的摄像机相关的状态。
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private boolean setUpCameraOutputs() {
        Activity activity = mActivity;
        //获取摄像头服务
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "setUpCameraOutputs: This device doesn't support Camera2 API.");
            return false;
        }
        try {
            // Find a CameraDevice that supports RAW captures, and configure state.
            // 查找支持RAW捕获的CameraDevice，并配置状态。
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We only use a camera that supports RAW in this sample.
                // 这里会检查摄像头支不支持传输原始数据，如果不支持的话会造成后面运行异常
                // 这里检查了REQUEST_AVAILABLE_CAPABILITIES数组里面有没有REQUEST_AVAILABLE_CAPABILITIES_RAW值
                // 这里测试发现只支持REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE最小功能集
                // 因此先把这段代码注释掉
                // 后面查一下是不是hal原因
//                if (!contains(characteristics.get(
//                                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
//                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
//                    continue;
//                }

                // 获取该相机支持的所有输出格式和尺寸，map存储
                // 注意这里已经自动帮我们进行了降序排序了
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // For still image captures, we use the largest available size.
                // 找到最大分辨率，这里由原来的min方法改为max方法
                // Collections类的min和max方法的实现原理都是使用迭代器模式
                // 这里传入了比较器CompareSizesByArea用来定制比较
                // 这里获取了JPEG格式的分辨率做比较，就算摄像头不支持JPEG也没关系，系统会转成JPEG的
                // 查看sdk源码发现getOutputSizes方法竟然不支持NV21和YUY2，用这两种格式会导致异常，所以这里用了JPEG格式
                Size[] sizeJPEGTemp = map.getOutputSizes(ImageFormat.JPEG);
                Size largestJpeg = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),//源码这里用了JPEG格式
                        new CompareSizesByArea());

                //测试用，修改照片的分辨率
//                Size jpegSize = new Size(800, 600);
//                largestJpeg = jpegSize;

                // 获取最大RAW_SENSOR格式，测试发现没有RAW_SENSOR格式，可能和hal有关，也可能和SDK有关
                // 这里改为YUV_420_888格式解决异常问题
                Size[] sizeYUVTemp = map.getOutputSizes(ImageFormat.YUV_420_888);
                Size largestYUV = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                        new CompareSizesByArea());
//                Size[] sizeRAWTemp = map.getOutputSizes(ImageFormat.RAW_SENSOR);
//                Size largestRaw = Collections.max(
//                        Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
//                        new CompareSizesByArea());
                //测试用，修改nv21预览数据分辨率
//                Size nv21Size = new Size(640, 480);
//                largestYUV = nv21Size;

                synchronized (mCameraStateLock) {
                    //添加nv21 ImageReader，用于接收预览数据并抛出nv21
                    if(mNV21ImageReader == null || mNV21ImageReader.getAndRetain() == null){
                        mNV21ImageReader = new RefCountedAutoCloseable<>(
                                ImageReader.newInstance(largestYUV.getWidth(),
                                        largestYUV.getHeight(), ImageFormat.YUV_420_888, 5));
                    }
                    //注意摄像头预览大小
                    if(mCameraPreviewSize == null){
                        mCameraPreviewSize = new Size(largestYUV.getWidth(), largestYUV.getHeight());
                    }
                    mNV21ImageReader.get().setOnImageAvailableListener(
                            mOnNV21ImageAvailableListener, mBackgroundHandler);


                    mCharacteristics = characteristics;
                    mCameraId = cameraId;
                }
                return true;
            }
        } catch (CameraAccessException e) {
//            e.printStackTrace();
//            Log.e(TAG, "setUpCameraOutputs: ", e);
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        }

        // If we found no suitable cameras for capturing RAW, warn the user.
        Log.e(TAG, "setUpCameraOutputs: This device doesn't support capturing RAW photos");
        return false;
    }

    /**
     * Permissions required to take a picture.
     * 拍照所需的权限。
     */
    private static final String[] CAMERA_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
    };

    /**
     * Tells whether all the necessary permissions are granted to this app.
     * 告知是否已将所有必要权限授予此应用。
     *
     * @return True if all the required permissions are granted.
     */
    private boolean hasAllPermissionsGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            if (ActivityCompat.checkSelfPermission(mActivity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link CameraDevice.StateCallback} is called when the currently active {@link CameraDevice}
     * changes its state.
     * 摄像头状态回调
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here if
            // the TextureView displaying this has been set up.
            synchronized (mCameraStateLock) {
                mState = STATE_OPENED;//表示摄像头已打开
                mCameraOpenCloseLock.release();//当摄像头打开成功了，才释放信号量
                mCameraDevice = cameraDevice;

                // Start the preview session if the TextureView has been set up already.
                // 如果已经设置TextureView，则开始预览会话。
                if (mPreviewSize != null && mTextureView.isAvailable()) {
                    createCameraPreviewSessionLocked();//创建摄像头预览
                }
            }
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();//若摄像头断开连接，释放信号量
                cameraDevice.close();//关闭摄像头
                mCameraDevice = null;
            }
        }

        //摄像头出错时回调，比如拔掉了usb摄像头
        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "Received camera device error: " + error);
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraOpenCloseLock.release();//若摄像头出错，释放信号量
                cameraDevice.close();
                mCameraDevice = null;
            }
            Activity activity = mActivity;
            if (null != activity) {
                activity.finish();//关闭app
            }
        }

    };
    
    /**
     * Opens the camera specified by {@link #mCameraId}.
     * 打开摄像头
     */
    @SuppressWarnings("MissingPermission")
    private void openCamera() {
        if (!setUpCameraOutputs()) {
            if (mCameraId == null) {
                if (cameraListener != null) {
                    cameraListener.onCameraError(new Exception("camera not found"));//抛出异常
                }
                return;
            }
            return;
        }
//        if (!hasAllPermissionsGranted()) {
//            Log.e(TAG, "openCamera: should request camera permissions");
//            return;
//        }

        Activity activity = mActivity;
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            // Wait for any previously running session to finish.
            // 等待任何先前运行的会话完成。
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {//在2.5s内尝试获得信号量通路
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            String cameraId;
            Handler backgroundHandler;
            synchronized (mCameraStateLock) {
                cameraId = mCameraId;
                backgroundHandler = mBackgroundHandler;
            }

            // Attempt to open the camera. mStateCallback will be called on the background handler's
            // thread when this succeeds or fails.
            // 尝试打开相机。 成功或失败时，将在后台处理程序的线程上调用mStateCallback。
            manager.openCamera(cameraId, mStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
//            e.printStackTrace();
//            Log.e(TAG, "setUpCameraOutputs: ", e);
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        } catch (InterruptedException e) {
//            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        }
    }

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events of a
     * {@link TextureView}.
     * 给TextureView对象添加回调函数，并在回调函数中将显示的图像进行旋转，保持和屏幕旋转角度一致
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);//将显示的图像进行旋转，保持和屏幕旋转角度一致
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);//将显示的图像进行旋转，保持和屏幕旋转角度一致
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            synchronized (mCameraStateLock) {
                mPreviewSize = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            Log.i(TAG, "onSurfaceTextureUpdated: do something");
        }

    };

    //启动相机的方法封装
    //包括了设置相机参数、设置预览、启动预览
    public void start() {
        startBackgroundThread();//启动一个后台线程
        openCamera();//打开摄像头

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we should
        // configure the preview bounds here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        // 当屏幕关闭并重新打开时，SurfaceTexture已经可用，并且不会调用“ onSurfaceTextureAvailable”。
        // 在那种情况下，我们应该在此处配置预览范围（否则，我们要等到SurfaceTextureListener中的surface准备好为止）。
        if (mTextureView.isAvailable()) {
            configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        if (mOrientationListener != null && mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }
    }

    //根据用户传入的屏幕旋转角度，得到摄像头旋转角度
    //移植sensorToDeviceRotation方法
    private int getCameraOri(int rotation) {
        int sensorOrientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);//获取摄像头方向

        // Get device orientation in degrees，获取设备旋转角度
        rotation = ORIENTATIONS.get(rotation);

        // Reverse device orientation for front-facing cameras
        // 前置摄像头的反向设备方向
        if (mCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            rotation = -rotation;
        }

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        // 计算相对于相机方向的所需JPEG方向，以使图像相对于设备方向垂直
        return (sensorOrientation - rotation + 360) % 360;
    }

    //关闭相机
    public void stop() {
        if (mOrientationListener != null) {
            mOrientationListener.disable();
        }
        closeCamera();
        stopBackgroundThread();
        if (cameraListener != null) {
            cameraListener.onCameraClosed();//通知用户相机关闭
        }
    }

    //判断相机状态
    public boolean isStopped() {
        synchronized (mCameraStateLock) {
            return mCameraDevice == null;
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
            mPreviewSize = null;
            mActivity = null;
        }
    }

    //获取相机支持的预览大小集合
    //camera2并没有获取预览大小的功能
    public List<Size> getSupportedPreviewSizes() {
        if (mCameraDevice == null || mCharacteristics == null) {
            return null;
        }
        //获取摄像头性能
        StreamConfigurationMap map = mCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
    }

    //获取相机支持的照片大小
    public List<Size> getSupportedPictureSizes() {
        if (mCameraDevice == null || mCharacteristics == null) {
            return null;
        }
        //获取摄像头性能
        StreamConfigurationMap map = mCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
    }

    //这里是获取到的NV21数据回调
    @Override
    public void onPreviewFrame(byte[] nv21, Camera camera) {
        if (cameraListener != null) {
            cameraListener.onPreview(nv21, camera);
        }
    }

    /**
     * Check if we are using a device that only supports the LEGACY hardware level.
     * <p/>
     * 检查我们是否使用的设备仅支持LEGACY硬件级别。
     * Call this only with {@link #mCameraStateLock} held.
     * 仅在保持{@link #mCameraStateLock}的情况下调用此函数。
     *
     * @return true if this is a legacy device.
     */
    private boolean isLegacyLocked() {
        return mCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
    }

    /**
     * Timeout for the pre-capture sequence.
     * 预先捕获序列的超时。
     */
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;

    /**
     * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is
     * taking too long.
     * 如果3A收敛时间过长，则可与预捕获序列配合使用以确保及时捕获。
     */
    private long mCaptureTimer;

    /**
     * Number of pending user requests to capture a photo.
     * 待拍照的未决用户请求数。
     */
    private int mPendingUserCaptures = 0;

    /**
     * Check if the timer for the pre-capture sequence has been hit.
     * <p/>
     * 检查是否已捕获预捕获序列的计时器。
     * Call this only with {@link #mCameraStateLock} held.
     * 仅在保持{@link #mCameraStateLock}的情况下调用此函数。
     *
     * @return true if the timeout occurred.
     */
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    /**
     * Send a capture request to the camera device that initiates a capture targeting the JPEG and
     * RAW outputs.
     * 向摄像机设备发送捕获请求，以启动针对JPEG/RAW/YUV输出的拍照捕获。
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void captureStillPictureLocked() {
        //单次捕获先不移植
    }

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events for the preview and
     * pre-capture sequence.
     * 处理预览和预捕获序列的事件。
     */
    private CameraCaptureSession.CaptureCallback mPreCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            synchronized (mCameraStateLock) {
                switch (mState) {
                    case STATE_PREVIEW: {
                        // We have nothing to do when the camera preview is running normally.
                        Log.d(TAG, "process: do nothing");
//                        takePicture();
                        break;
                    }
                    case STATE_WAITING_FOR_3A_CONVERGENCE: {
                        boolean readyToCapture = true;
                        if (!mNoAFRun) {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                break;
                            }

                            // If auto-focus has reached locked state, we are ready to capture
                            // 如果自动对焦已达到锁定状态，我们准备好进行捕捉
                            readyToCapture =
                                    (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                        }

                        // If we are running on an non-legacy device, we should also wait until
                        // auto-exposure and auto-white-balance have converged as well before
                        // taking a picture.
                        // 如果我们在非旧版设备上运行，则还应该等到自动曝光和自动白平衡收敛后再拍照。
                        if (!isLegacyLocked()) {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                            if (aeState == null || awbState == null) {
                                break;
                            }

                            readyToCapture = readyToCapture &&
                                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                    awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
                        }

                        // If we haven't finished the pre-capture sequence but have hit our maximum
                        // wait timeout, too bad! Begin capture anyway.
                        // 如果我们尚未完成捕获前的序列，但达到了最大等待超时，那就太糟糕了！ 无论如何都开始捕获。
                        if (!readyToCapture && hitTimeoutLocked()) {
                            Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                            readyToCapture = true;
                        }

                        if (readyToCapture && mPendingUserCaptures > 0) {
                            // Capture once for each user tap of the "Picture" button.
                            // 为每个用户点击“图片”按钮捕获一次。
                            while (mPendingUserCaptures > 0) {
                                captureStillPictureLocked();
                                mPendingUserCaptures--;
                            }
                            // After this, the camera will go back to the normal state of preview.
                            mState = STATE_PREVIEW;
                        }
                    }
                }
            }
        }

        /**
         * This method is called when an image capture makes partial forward progress; some
         * (but not all) results from an image capture are available.
         * 当图像捕获部分向前进行时，将调用此方法。 可以使用某些（但不是全部）图像捕获结果。
         *
         * <p>The result provided here will contain some subset of the fields of
         * a full result. Multiple {@link #onCaptureProgressed} calls may happen per
         * capture; a given result field will only be present in one partial
         * capture at most. The final {@link #onCaptureCompleted} call will always
         * contain all the fields (in particular, the union of all the fields of all
         * the partial results composing the total result).</p>
         * 此处提供的结果将包含完整结果的某些字段子集。 每次捕获可能会发生多个{@link #onCaptureProgressed}调用；
         * 给定的结果字段最多只会出现在一个部分捕获中。
         * 最后的{@link #onCaptureCompleted}调用将始终包含所有字段（尤其是组成总结果的所有部分结果的所有字段的并集）。
         *
         * <p>For each request, some result data might be available earlier than others. The typical
         * delay between each partial result (per request) is a single frame interval.
         * For performance-oriented use-cases, applications should query the metadata they need
         * to make forward progress from the partial results and avoid waiting for the completed
         * result.</p>
         * 对于每个请求，某些结果数据可能早于其他结果。 每个部分结果（每个请求）之间的典型延迟是单个帧间隔。
         * 对于面向性能的用例，应用程序应查询所需的元数据以从部分结果中取得进展并避免等待完整的结果。
         *
         * <p>For a particular request, {@link #onCaptureProgressed} may happen before or after
         * {@link #onCaptureStarted}.</p>
         * 对于特定请求，{@ link #onCaptureProgressed}可能在{@link #onCaptureStarted}之前或之后发生。
         *
         * <p>Each request will generate at least {@code 1} partial results, and at most
         * {@link CameraCharacteristics#REQUEST_PARTIAL_RESULT_COUNT} partial results.</p>
         * 每个请求将至少生成{@code 1}个部分结果，最多{@link CameraCharacteristics＃REQUEST_PARTIAL_RESULT_COUNT}个部分结果。
         *
         *
         * <p>Depending on the request settings, the number of partial results per request
         * will vary, although typically the partial count could be the same as long as the
         * camera device subsystems enabled stay the same.</p>
         * 根据请求设置，每个请求的部分结果数将有所变化，尽管通常只要启用的摄像头设备子系统保持不变，部分计数就可以相同。
         *
         * <p>The default implementation of this method does nothing.</p>
         * 此方法的默认实现不执行任何操作。
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request The request that was given to the CameraDevice
         * @param partialResult The partial output metadata from the capture, which
         * includes a subset of the {@link TotalCaptureResult} fields.
         *                      捕获的部分输出元数据，包括{@link TotalCaptureResult}字段的子集。
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureResult partialResult) {
            process(partialResult);
        }

        /**
         * This method is called when an image capture has fully completed and all the
         * result metadata is available.
         * 当图像捕获完全完成并且所有结果元数据都可用时，将调用此方法。
         *
         * <p>This callback will always fire after the last {@link #onCaptureProgressed};
         * in other words, no more partial results will be delivered once the completed result
         * is available.</p>
         * 此回调将始终在最后一个{@link #onCaptureProgressed}之后触发；换句话说，一旦完成的结果可用，将不再传递部分结果。
         *
         * <p>For performance-intensive use-cases where latency is a factor, consider
         * using {@link #onCaptureProgressed} instead.</p>
         * 对于延迟是影响因素的性能密集型用例，请考虑改用{@link #onCaptureProgressed}。
         *
         * <p>The default implementation of this method does nothing.</p>
         * 此方法的默认实现不执行任何操作。
         *
         * @param session the session returned by {@link CameraDevice#createCaptureSession}
         * @param request The request that was given to the CameraDevice
         * @param result The total output metadata from the capture, including the
         * final capture parameters and the state of the camera system during
         * capture.捕获的总输出元数据，包括最终捕获参数和捕获期间相机系统的状态。
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                       TotalCaptureResult result) {
            process(result);

        }

    };

    // TextureView标准写法，重写下面的方法
    // 唯一要做的就是获取用于渲染内容的SurfaceTexture
    private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        // 在调用TextureView的draw方法时，如果还没有初始化SurfaceTexture。那么就会初始化它。
        // 初始化好时，就会回调这个接口。SurfaceTexture初始化好时，
        // 就表示可以接收外界的绘制指令了（可以异步接收）。
        // 然后SurfaceTexture会以GL纹理信息更新到TextureView对应的HardwareLayer中。然后就会在HardwareLayer中显示。
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);//将显示的图像进行旋转，保持和屏幕旋转角度一致
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(TAG, "onSurfaceTextureSizeChanged: " + width + "  " + height);
            configureTransform(width, height);//将显示的图像进行旋转，保持和屏幕旋转角度一致
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
            configureTransform(mSurfaceView.getWidth(), mSurfaceView.getHeight());//将显示的图像进行旋转，保持和屏幕旋转角度一致
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            configureTransform(mSurfaceView.getWidth(), mSurfaceView.getHeight());//将显示的图像进行旋转，保持和屏幕旋转角度一致
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            stop();
        }
    };

    //改变显示角度
    public void changeDisplayOrientation(int rotation) {
        if (mCameraDevice != null) {
            this.rotation = rotation;
            displayOrientation = getCameraOri(rotation);
            configureTransform(mPreviewSize.getWidth(), mPreviewSize.getHeight(), displayOrientation);
            if (cameraListener != null) {
                cameraListener.onCameraConfigurationChanged(Integer.valueOf(mCameraId), displayOrientation);
            }
        }
    }

    //切换相机
    public boolean switchCamera(){
        int num = 0;
        CameraManager manager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return false;
        }

        try{
            if(manager.getCameraIdList().length < 2){
                return false;
            }
        }catch (CameraAccessException e){
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        }


        // cameraId ,0为后置，1为前置
        int cameraID = 1 - Integer.valueOf(mCameraId);
        mCameraId = String.valueOf(cameraID);
        stop();
        start();
        return true;
    }

    /**
     * Stops the background thread and its {@link Handler}.
     * 停止后台线程及其{@link Handler}。
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            synchronized (mCameraStateLock) {
                mBackgroundHandler = null;
            }
        } catch (InterruptedException e) {
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
//            Log.e(TAG, "stopBackgroundThread: ", e);
//            e.printStackTrace();
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     * 关闭当前的{@link CameraDevice}。
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();//同步开始
            /**
             * 在 semaphore.acquire() 和 semaphore.release()之间的代码，同一时刻只允许制定个数的线程进入，
             * 因为semaphore的构造方法是1，则同一时刻只允许一个线程进入，其他线程只能等待。
             * */
            synchronized (mCameraStateLock) {

                mState = STATE_CLOSED;
                if (null != mCaptureSession) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
                if (null != mCameraDevice) {
                    mCameraDevice.close();
                    mCameraDevice = null;
                }
                if(null != mNV21ImageReader){
                    mNV21ImageReader.close();
                    mNV21ImageReader = null;
                }
            }
        } catch (InterruptedException e) {
//            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        } finally {
            mCameraOpenCloseLock.release();//同步结束
        }
    }

    /**
     * Rotation need to transform from the camera sensor orientation to the device's current
     * orientation.
     * 计算出当前摄像头和显示屏的相对角度
     *
     * @param c                 the {@link CameraCharacteristics} to query for the camera sensor
     *                          orientation.
     *                          相机方向
     * @param deviceOrientation the current device orientation relative to the native device
     *                          orientation.
     *                          当前设备方向相对于本地设备方向
     * @return the total rotation from the sensor orientation to the current device orientation.
     * 从传感器方向到当前设备方向的总旋转。
     */
    private static int sensorToDeviceRotation(CameraCharacteristics c, int deviceOrientation) {
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);//获取摄像头方向

        // Get device orientation in degrees，获取设备旋转角度
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);

        // Reverse device orientation for front-facing cameras
        // 前置摄像头的反向设备方向
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            deviceOrientation = -deviceOrientation;
        }

        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        // 计算相对于相机方向的所需JPEG方向，以使图像相对于设备方向垂直
        return (sensorOrientation - deviceOrientation + 360) % 360;
    }

    /**
     * Configure the necessary {@link android.graphics.Matrix} transformation to `mTextureView`,
     * and start/restart the preview capture session if necessary.
     * <p/>
     * This method should be called after the camera state has been initialized in
     * setUpCameraOutputs.
     *
     * 此方法用来将预览显示的图像旋转角度和屏幕旋转角度保持一致
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        synchronized (mCameraStateLock) {
            if (null == mTextureView) {
                return;
            }

            //获取摄像头性能
            StreamConfigurationMap map = mCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // For still image captures, we always use the largest available size.
            // 找到JPEG支持的最大分辨率
            Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            // Find the rotation of the device relative to the native device orientation.
            int deviceRotation = rotation;//获取当前屏幕旋转角度
            //获取当前活动的宽高，和旋转后的方向没有关系
            //注意，此宽高和屏幕真实分辨率不一样，如果屏幕有导航栏，就会减掉导航栏的尺寸
            //真实分辨率用getRealMetrics方法
            Point displaySize = previewViewSize;

            // Find the rotation of the device relative to the camera sensor's orientation.
            // 计算摄像头和显示屏的相对角度
            int totalRotation = sensorToDeviceRotation(mCharacteristics, deviceRotation);

            // Swap the view dimensions for calculation as needed if they are rotated relative to
            // the sensor.
            // 如果视图尺寸相对于传感器旋转，请根据需要交换它们以进行计算。
            boolean swappedDimensions = totalRotation == 90 || totalRotation == 270;
            int rotatedViewWidth = viewWidth;
            int rotatedViewHeight = viewHeight;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;

            if (swappedDimensions) {
                rotatedViewWidth = viewHeight;
                rotatedViewHeight = viewWidth;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            // Preview should not be larger than display size and 1080p.
            // 限制预览大小
            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            // Find the best preview size for these view dimensions and configured JPEG size.
            // 找到最合适分辨率
            Size previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight,
                    largestJpeg);

            //测试用，修改预览分辨率
//            Size previewSize2 = new Size(1280, 720);
//            previewSize = previewSize2;

            // 判断是否需要对调宽高
//            if(mSetAspectRatio){
//                if (swappedDimensions) {
//                    mTextureView.setAspectRatio(
//                            previewSize.getHeight(), previewSize.getWidth());
//                } else {
//                    mTextureView.setAspectRatio(
//                            previewSize.getWidth(), previewSize.getHeight());
//                }
//            }

            // Find rotation of device in degrees (reverse device orientation for front-facing
            // cameras).
            // 以度为单位查找设备旋转（前置摄像头的设备反向）
            int rotation = (mCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT) ?
                    (360 + ORIENTATIONS.get(deviceRotation)) % 360 :
                    (360 - ORIENTATIONS.get(deviceRotation)) % 360;

            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            // Initially, output stream images from the Camera2 API will be rotated to the native
            // device orientation from the sensor's orientation, and the TextureView will default to
            // scaling these buffers to fill it's view bounds.  If the aspect ratios and relative
            // orientations are correct, this is fine.
            // 最初，Camera2 API的输出流图像将从传感器的方向旋转到本机设备的方向，
            // 并且TextureView将默认缩放这些缓冲区以填充其视图范围。
            // 如果长宽比和相对方向正确，则可以。
            //
            // However, if the device orientation has been rotated relative to its native
            // orientation so that the TextureView's dimensions are swapped relative to the
            // native device orientation, we must do the following to ensure the output stream
            // images are not incorrectly scaled by the TextureView:
            // 但是，如果设备方向已相对于其原始方向旋转，因此TextureView的尺寸相对于原始设备方向进行了交换，
            // 则必须执行以下操作以确保TextureView不会错误地缩放输出流图像：
            //   - Undo the scale-to-fill from the output buffer's dimensions (i.e. its dimensions
            //     in the native device orientation) to the TextureView's dimension.
            //   - 从输出缓冲区的尺寸（即其在本机设备方向上的尺寸）到TextureView的尺寸，撤消要填充的比例。
            //   - Apply a scale-to-fill from the output buffer's rotated dimensions
            //     (i.e. its dimensions in the current device orientation) to the TextureView's
            //     dimensions.
            //   - 从输出缓冲区的旋转尺寸（即当前设备方向的尺寸）到TextureView的尺寸应用比例填充。
            //   - Apply the rotation from the native device orientation to the current device
            //     rotation.
            //   - 将旋转从本地设备方向应用于当前设备旋转
            if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max(
                        (float) viewHeight / previewSize.getHeight(),
                        (float) viewWidth / previewSize.getWidth());
                matrix.postScale(scale, scale, centerX, centerY);

            }
            matrix.postRotate(rotation, centerX, centerY);

            mTextureView.setTransform(matrix);

            // Start or restart the active capture session if the preview was initialized or
            // if its aspect ratio changed significantly.
            // 如果预览已初始化或其宽高比有很大变化，请启动或重新启动活动捕获会话。
            if (mPreviewSize == null || !checkAspectsEqual(previewSize, mPreviewSize)) {
                mPreviewSize = previewSize;//确定最终的预览分辨率大小
                if (mState != STATE_CLOSED) {
                    createCameraPreviewSessionLocked();//创建摄像头预览
                }
            }
        }
    }

    //重载configureTransform，自定义预览角度
    private void configureTransform(int viewWidth, int viewHeight, int rotation) {
        synchronized (mCameraStateLock) {
            if (null == mTextureView) {
                return;
            }

            //获取摄像头性能
            StreamConfigurationMap map = mCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // For still image captures, we always use the largest available size.
            // 找到JPEG支持的最大分辨率
            Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());

            // Find the rotation of the device relative to the native device orientation.
            int deviceRotation = rotation;//获取当前屏幕旋转角度
            //获取当前活动的宽高，和旋转后的方向没有关系
            //注意，此宽高和屏幕真实分辨率不一样，如果屏幕有导航栏，就会减掉导航栏的尺寸
            //真实分辨率用getRealMetrics方法
            Point displaySize = previewViewSize;

            // Find the rotation of the device relative to the camera sensor's orientation.
            // 计算摄像头和显示屏的相对角度
            int totalRotation = sensorToDeviceRotation(mCharacteristics, deviceRotation);

            // Swap the view dimensions for calculation as needed if they are rotated relative to
            // the sensor.
            // 如果视图尺寸相对于传感器旋转，请根据需要交换它们以进行计算。
            boolean swappedDimensions = totalRotation == 90 || totalRotation == 270;
            int rotatedViewWidth = viewWidth;
            int rotatedViewHeight = viewHeight;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;

            if (swappedDimensions) {
                rotatedViewWidth = viewHeight;
                rotatedViewHeight = viewWidth;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }

            // Preview should not be larger than display size and 1080p.
            // 限制预览大小
            if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                maxPreviewWidth = MAX_PREVIEW_WIDTH;
            }

            if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                maxPreviewHeight = MAX_PREVIEW_HEIGHT;
            }

            // Find the best preview size for these view dimensions and configured JPEG size.
            // 找到最合适分辨率
            Size previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    rotatedViewWidth, rotatedViewHeight, maxPreviewWidth, maxPreviewHeight,
                    largestJpeg);

            //测试用，修改预览分辨率
//            Size previewSize2 = new Size(1280, 720);
//            previewSize = previewSize2;

            // 判断是否需要对调宽高
//            if(mSetAspectRatio){
//                if (swappedDimensions) {
//                    mTextureView.setAspectRatio(
//                            previewSize.getHeight(), previewSize.getWidth());
//                } else {
//                    mTextureView.setAspectRatio(
//                            previewSize.getWidth(), previewSize.getHeight());
//                }
//            }

            // Find rotation of device in degrees (reverse device orientation for front-facing
            // cameras).
            // 以度为单位查找设备旋转（前置摄像头的设备反向）
            int rotationTemp = (mCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT) ?
                    (360 + ORIENTATIONS.get(deviceRotation)) % 360 :
                    (360 - ORIENTATIONS.get(deviceRotation)) % 360;

            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            // Initially, output stream images from the Camera2 API will be rotated to the native
            // device orientation from the sensor's orientation, and the TextureView will default to
            // scaling these buffers to fill it's view bounds.  If the aspect ratios and relative
            // orientations are correct, this is fine.
            // 最初，Camera2 API的输出流图像将从传感器的方向旋转到本机设备的方向，
            // 并且TextureView将默认缩放这些缓冲区以填充其视图范围。
            // 如果长宽比和相对方向正确，则可以。
            //
            // However, if the device orientation has been rotated relative to its native
            // orientation so that the TextureView's dimensions are swapped relative to the
            // native device orientation, we must do the following to ensure the output stream
            // images are not incorrectly scaled by the TextureView:
            // 但是，如果设备方向已相对于其原始方向旋转，因此TextureView的尺寸相对于原始设备方向进行了交换，
            // 则必须执行以下操作以确保TextureView不会错误地缩放输出流图像：
            //   - Undo the scale-to-fill from the output buffer's dimensions (i.e. its dimensions
            //     in the native device orientation) to the TextureView's dimension.
            //   - 从输出缓冲区的尺寸（即其在本机设备方向上的尺寸）到TextureView的尺寸，撤消要填充的比例。
            //   - Apply a scale-to-fill from the output buffer's rotated dimensions
            //     (i.e. its dimensions in the current device orientation) to the TextureView's
            //     dimensions.
            //   - 从输出缓冲区的旋转尺寸（即当前设备方向的尺寸）到TextureView的尺寸应用比例填充。
            //   - Apply the rotation from the native device orientation to the current device
            //     rotation.
            //   - 将旋转从本地设备方向应用于当前设备旋转
            if (Surface.ROTATION_90 == deviceRotation || Surface.ROTATION_270 == deviceRotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                float scale = Math.max(
                        (float) viewHeight / previewSize.getHeight(),
                        (float) viewWidth / previewSize.getWidth());
                matrix.postScale(scale, scale, centerX, centerY);

            }
            matrix.postRotate(rotationTemp, centerX, centerY);

            mTextureView.setTransform(matrix);

            // Start or restart the active capture session if the preview was initialized or
            // if its aspect ratio changed significantly.
            // 如果预览已初始化或其宽高比有很大变化，请启动或重新启动活动捕获会话。
            if (mPreviewSize == null || !checkAspectsEqual(previewSize, mPreviewSize)) {
                mPreviewSize = previewSize;//确定最终的预览分辨率大小
                if (mState != STATE_CLOSED) {
                    createCameraPreviewSessionLocked();//创建摄像头预览
                }
            }
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     *
     * 创建摄像头预览
     */
    private void createCameraPreviewSessionLocked() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            // We configure the size of default buffer to be the size of camera preview we want.
            // 我们将默认缓冲区的大小配置为所需的摄像机预览的大小。
            // 同时这里也是设置摄像头预览分辨率的大小
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);//创建预览请求
            //将textureview作为接收预览数据的目标
            mPreviewRequestBuilder.addTarget(surface);
            //把nv21的ImageReader的surface添加进去
            //经测试如果添加另一个surface用于接收预览数据，会造成textureview显示的预览卡顿问题，待解决
            mPreviewRequestBuilder.addTarget(mNV21ImageReader.get().getSurface());


            // Here, we create a CameraCaptureSession for camera preview.
            // 在这里，我们创建了CameraCaptureSession来进行摄像机预览
            // 注意把要ImageReader的surface放进去
            // 注意这里如果不添加surface，addTarget添加的surface也没用
            mCameraDevice.createCaptureSession(Arrays.asList(surface,
                    mNV21ImageReader.get().getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        /**
                         * This method is called when the camera device has finished configuring itself, and the
                         * session can start processing capture requests.
                         * 摄像头设备完成自身配置后，将调用此方法，并且会话可以开始处理捕获请求。
                         *
                         * <p>If there are capture requests already queued with the session, they will start
                         * processing once this callback is invoked, and the session will call {@link #onActive}
                         * right after this callback is invoked.</p>
                         * 如果有捕获请求已在会话中排队，则将在调用此回调后立即开始处理，
                         * 并且在调用此回调后会话将立即调用{@link #onActive}。
                         *
                         * <p>If no capture requests have been submitted, then the session will invoke
                         * {@link #onReady} right after this callback.</p>
                         * 如果没有提交捕获请求，则会话将在此回调之后立即调用{@link #onReady}。
                         *
                         * <p>If the camera device configuration fails, then {@link #onConfigureFailed} will
                         * be invoked instead of this callback.</p>
                         * 如果摄像头设备配置失败，则将调用{@link #onConfigureFailed}而不是此回调。
                         *
                         * @param session the session returned by {@link CameraDevice#createCaptureSession}
                         */
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            synchronized (mCameraStateLock) {
                                // The camera is already closed
                                if (null == mCameraDevice) {
                                    return;
                                }

                                try {
                                    setup3AControlsLocked(mPreviewRequestBuilder);//设置3A
                                    // Finally, we start displaying the camera preview.
                                    // 开始摄像头启动预览，执行这条语句后，在surface监听回调中加断点能够停止预览图像，
                                    // 而在CaptureSession中任何回调位置中加断点都不会停止预览图像
                                    cameraCaptureSession.setRepeatingRequest(
                                            mPreviewRequestBuilder.build(),
                                            mPreCaptureCallback, mBackgroundHandler);
                                    mState = STATE_PREVIEW;
                                    if(cameraListener != null){
                                        //通知用户打开的相机信息
                                        cameraListener.onCamera2Opened(mCameraPreviewSize, Integer.valueOf(mCameraId), displayOrientation, isMirror);
                                    }
                                } catch (CameraAccessException | IllegalStateException e) {
//                                    e.printStackTrace();
//                                    Log.e(TAG, "setUpCameraOutputs: ", e);
                                    if (cameraListener != null) {
                                        cameraListener.onCameraError(e);
                                    }
                                    return;
                                }
                                // When the session is ready, we start displaying the preview.
                                mCaptureSession = cameraCaptureSession;
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "onConfigureFailed: Failed to configure camera.");
                        }
                    }, mBackgroundHandler
            );
        } catch (CameraAccessException e) {
//            e.printStackTrace();
//            Log.e(TAG, "setUpCameraOutputs: ", e);
            if (cameraListener != null) {
                cameraListener.onCameraError(e);
            }
        }
    }

    /**
     * Return true if the given array contains the given integer.
     * 检查int数组里面有没有包含某个值
     *
     * @param modes array to check.
     * @param mode  integer to get for.
     * @return true if the array contains the given integer, otherwise false.
     */
    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Configure the given {@link CaptureRequest.Builder} to use auto-focus, auto-exposure, and
     * auto-white-balance controls if available.
     * <p/>
     * Call this only with {@link #mCameraStateLock} held.
     *
     * 通过会话设置3A
     *
     * @param builder the builder to configure.
     */
    private void setup3AControlsLocked(CaptureRequest.Builder builder) {
        // Enable auto-magical 3A run by camera device
        // 为每个3A例程使用设置。
        // 捕获参数的手动控制已禁用。 android.control。*中的所有控件（除了SceneMode之外）均生效
        builder.set(CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        // 如果MINIMUM_FOCUS_DISTANCE为0，则镜头为固定焦点，我们需要跳过自动对焦。
        mNoAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!mNoAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            // 如果有“连续图片”模式可用，请使用它，否则默认为“自动”。
            if (contains(mCharacteristics.get(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        // 如果有自动曝光加自动闪光灯控制模式，请使用它，否则默认为
        // 保证始终可用的“开启”模式。
        if (contains(mCharacteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)) {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
        }

        // If there is an auto-magical white balance control mode available, use it.
        // 如果有自动魔术白平衡控制模式，请使用它。
        if (contains(mCharacteristics.get(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            // 如果此设备支持AWB，则允许AWB自动运行
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }

    /**
     * Return true if the two given {@link Size}s have the same aspect ratio.
     * 如果两个给定的{@link Size}具有相同的宽高比，则返回true。
     *
     * @param a first {@link Size} to compare.
     * @param b second {@link Size} to compare.
     * @return true if the sizes have the same aspect ratio, otherwise false.
     */
    private static boolean checkAspectsEqual(Size a, Size b) {
        double aAspect = a.getWidth() / (double) a.getHeight();
        double bAspect = b.getWidth() / (double) b.getHeight();
        return Math.abs(aAspect - bAspect) <= ASPECT_RATIO_TOLERANCE;
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     * 给定照相机支持的{@code Size}个{@code choices}，请选择至少与相应的纹理视图大小一样大，
     * 并且最大与相应的最大大小一样大的最小的， 长宽比与指定值匹配。
     * 如果不存在这样的大小，请选择最大与最大大小一样大且纵横比与指定值匹配的最大大小。
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     *                          相机支持的预期输出类别的尺寸列表
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     *                          纹理视图相对于传感器坐标的宽度
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     *                          纹理视图相对于传感器坐标的高度
     * @param maxWidth          The maximum width that can be chosen
     *                          可以选择的最大宽度
     * @param maxHeight         The maximum height that can be chosen
     *                          可以选择的最大高度
     * @param aspectRatio       The aspect ratio
     *                          长宽比
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     * 最佳{@code Size}，如果没有足够大，则为任意值
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {

        // compare方法返回的值代表了偏差值，有了偏差值，就能根据偏差值找到最大、最小等元素了
        // 所以自定义compare方法，就是自定义偏差值的计算方式，实现了自定义的比较形式
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            // 在这里强制转换以确保乘法不会溢出
            // signum方法是用来判断正负的，只会返回-1,0,1
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * A {@link TextureView} that can be adjusted to a specified aspect ratio.
     */
    public class AutoFitTextureView extends TextureView {

        private TextureView mFather;
        private int mRatioWidth = 0;//选择的摄像头宽
        private int mRatioHeight = 0;//选择的摄像头高

        public AutoFitTextureView(Context context) {
            this(context, null);
        }

        public AutoFitTextureView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        public TextureView getFatherTextureView(){
            return mFather;
        }

        public void setFatherTextureView(TextureView father){
            mFather = father;
        }

        /**
         * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
         * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
         * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
         * 设置此视图的纵横比。 视图的大小将根据从参数计算出的比率进行测量。
         * 请注意，参数的实际大小无关紧要，也就是说，
         * 调用setAspectRatio（2，3）和setAspectRatio（4，6）会得到相同的结果。
         *
         * @param width  Relative horizontal size
         * @param height Relative vertical size
         */
        public void setAspectRatio(int width, int height) {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("Size cannot be negative.");
            }
            if (mRatioWidth == width && mRatioHeight == height) {
                return;
            }
            mRatioWidth = width;
            mRatioHeight = height;
            requestLayout();
        }

        // 设置textureView宽高，使得适配mRatioWidth和mRatioHeight
        // mRatioWidth和mRatioHeight表示选择的摄像头宽高
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            //根据mRatioWidth和mRatioHeight重新设置TextureView尺寸
            if (0 == mRatioWidth || 0 == mRatioHeight) {
                setMeasuredDimension(width, height);
            } else {
                if (width < height * mRatioWidth / mRatioHeight) {
                    setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
                } else {
                    setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
                }
            }
        }

    }

    /**
     * A wrapper for an {@link AutoCloseable} object that implements reference counting to allow
     * for resource management.
     * {@link AutoCloseable}对象的包装，该包装实现引用计数以允许进行资源管理。
     */
    public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
        private T mObject;
        private long mRefCount = 0;

        /**
         * Wrap the given object.
         * 包装给定的对象。
         *
         * @param object an object to wrap.
         */
        public RefCountedAutoCloseable(T object) {
            if (object == null) throw new NullPointerException();
            mObject = object;
        }

        /**
         * Increment the reference count and return the wrapped object.
         * 增加引用计数并返回包装的对象。
         *
         * @return the wrapped object, or null if the object has been released.
         */
        public synchronized T getAndRetain() {
            if (mRefCount < 0) {
                return null;
            }
            mRefCount++;
            return mObject;
        }

        /**
         * Return the wrapped object.
         * 返回包装的对象。
         *
         * @return the wrapped object, or null if the object has been released.
         * @返回包装的对象；如果已释放对象，则返回null。
         */
        public synchronized T get() {
            return mObject;
        }

        /**
         * Decrement the reference count and release the wrapped object if there are no other
         * users retaining this object.
         * 如果没有其他用户保留该对象，则减少引用计数并释放包装的对象。
         */
        @Override
        public synchronized void close() {
            if (mRefCount >= 0) {
                mRefCount--;
                if (mRefCount < 0) {
                    try {
                        mObject.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        mObject = null;
                    }
                }
            }
        }
    }

    /**
     * yuv420p: yyyyyyyyuuvv
     * yuv420sp: yyyyyyyyuvuv
     * nv21: yyyyyyyyvuvu
     */
    public static class ImageUtil {
        public static final int YUV420P = 0;
        public static final int YUV420SP = 1;
        public static final int NV21 = 2;
        private static final String TAG = "ImageUtil";

        /***
         * 此方法内注释以640*480为例
         * 未考虑CropRect的
         */
        public static byte[] getBytesFromImageAsType(Image image, int type) {
            try {
                // 获取源数据，如果是YUV格式的数据planes.length = 3
                // plane[i]里面的实际数据可能存在byte[].length <= capacity (缓冲区总大小)
                final Image.Plane[] planes = image.getPlanes();
                int length = planes.length;
                if(length != 3){
                    Log.w(TAG, "getBytesFromImageAsType: planes length != 3");
                    return null;
                }
                // 数据有效宽度，一般的，图片width <= rowStride，这也是导致byte[].length <= capacity的原因
                // 所以我们只取width部分
                int width = image.getWidth();
                int height = image.getHeight();

                //此处用来装填最终的YUV数据，需要1.5倍的图片大小，因为Y U V 比例为 4:1:1
                int pixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888);//获取单个像素占用的位数
                int yuvBytesSize = width * height * pixel / 8;
                byte[] yuvBytes = new byte[width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];
                //目标数组的装填到的位置
                int dstIndex = 0;

                //临时存储uv数据的
                byte uBytes[] = new byte[width * height / 4];
                byte vBytes[] = new byte[width * height / 4];
                int uIndex = 0;
                int vIndex = 0;

                int pixelsStride, rowStride;
                for (int i = 0; i < planes.length; i++) {
                    pixelsStride = planes[i].getPixelStride();//pixelStride 代表行内颜色值间隔，一般取1或2，取1表示无间隔
                    rowStride = planes[i].getRowStride();//一般代表行分辨率，比如例子是640

                    ByteBuffer buffer = planes[i].getBuffer();

                    //如果pixelsStride==2，一般的Y的buffer长度=640*480，UV的长度=640*480/2-1
                    //源数据的索引，y的数据是byte中连续的，u的数据是v向左移以为生成的，两者都是偶数位为有效数据
                    int bufferCap = buffer.capacity();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);//把ByteBuffer类的有效数据取出来

                    int srcIndex = 0;
                    if (i == 0) {
                        //直接取出来所有Y的有效区域，也可以存储成一个临时的bytes，到下一步再copy
                        //这里可以优化为一次copy height*width
                        for (int j = 0; j < height; j++) {
                            System.arraycopy(bytes, srcIndex, yuvBytes, dstIndex, width);
                            srcIndex += rowStride;
                            dstIndex += width;
                        }
                    } else if (i == 1) {
                        //根据pixelsStride取相应的数据
                        for (int j = 0; j < height / 2; j++) {
                            for (int k = 0; k < width / 2; k++) {
                                uBytes[uIndex++] = bytes[srcIndex];
                                srcIndex += pixelsStride;
                            }
                            if (pixelsStride == 2) {
                                srcIndex += rowStride - width;
                            } else if (pixelsStride == 1) {
                                srcIndex += rowStride - width / 2;
                            }
                        }
                    } else if (i == 2) {
                        //根据pixelsStride取相应的数据
                        for (int j = 0; j < height / 2; j++) {
                            for (int k = 0; k < width / 2; k++) {
                                vBytes[vIndex++] = bytes[srcIndex];
                                srcIndex += pixelsStride;
                            }
                            if (pixelsStride == 2) {
                                srcIndex += rowStride - width;
                            } else if (pixelsStride == 1) {
                                srcIndex += rowStride - width / 2;
                            }
                        }
                    }
                }

                image.close();

                //根据要求的结果类型进行填充
                switch (type) {
                    case YUV420P:
                        System.arraycopy(uBytes, 0, yuvBytes, dstIndex, uBytes.length);
                        System.arraycopy(vBytes, 0, yuvBytes, dstIndex + uBytes.length, vBytes.length);
                        break;
                    case YUV420SP:
                        for (int i = 0; i < vBytes.length; i++) {
                            yuvBytes[dstIndex++] = uBytes[i];
                            yuvBytes[dstIndex++] = vBytes[i];
                        }
                        break;
                    case NV21:
                        for (int i = 0; i < vBytes.length; i++) {
                            yuvBytes[dstIndex++] = vBytes[i];
                            yuvBytes[dstIndex++] = uBytes[i];
                        }
                        break;
                }
                return yuvBytes;
            } catch (final Exception e) {
                if (image != null) {
                    image.close();
                }
                Log.i(TAG, e.toString());
            }
            return null;
        }

        // YV12 To NV21
        public static void YV12toNV21(final byte[] input, final byte[] output, final int width, final int height) {
            long startMs = System.currentTimeMillis();
            final int frameSize = width * height;
            final int qFrameSize = frameSize / 4;
            final int tempFrameSize = frameSize * 5 / 4;

            System.arraycopy(input, 0, output, 0, frameSize); // Y

            for (int i = 0; i < qFrameSize; i++) {
                output[frameSize + i * 2] = input[frameSize + i]; // Cb (U)
                output[frameSize + i * 2 + 1] = input[tempFrameSize + i]; // Cr (V)
            }
        }

        //I420 To NV21
        public static void I420ToNV21(final byte[] input, final byte[] output, final int width, final int height) {
            //long startMs = System.currentTimeMillis();
            final int frameSize = width * height;
            final int qFrameSize = frameSize / 4;
            final int tempFrameSize = frameSize * 5 / 4;

            System.arraycopy(input, 0, output, 0, frameSize); // Y

            for (int i = 0; i < qFrameSize; i++) {
                output[frameSize + i * 2] = input[tempFrameSize + i]; // Cb (U)
                output[frameSize + i * 2 + 1] = input[frameSize + i]; // Cr (V)
            }
        }

        //I420 To NV21
        public static byte[] I420Tonv21(byte[] data, int width, int height) {
            byte[] ret = new byte[data.length];
            int total = width * height;

            ByteBuffer bufferY = ByteBuffer.wrap(ret, 0, total);
            ByteBuffer bufferV = ByteBuffer.wrap(ret, total, total / 4);
            ByteBuffer bufferU = ByteBuffer.wrap(ret, total + total / 4, total / 4);

            bufferY.put(data, 0, total);
            for (int i = 0; i < total / 4; i += 1) {
                bufferV.put(data[total + i]);
                bufferU.put(data[i + total + total / 4]);
            }

            return ret;
        }

        //NV21转NV12互换
        public static void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height){
            if(nv21 == null || nv12 == null)return;
            int framesize = width*height;
            int i,j;
            System.arraycopy(nv21, 0, nv12, 0, framesize);
            for(i = 0; i < framesize; i++){
                nv12[i] = nv21[i];
            }
            for (j = 1; j < framesize/2; j+=2)
            {
                nv12[framesize + j-1] = nv21[j+framesize];
            }
            for (j = 1; j < framesize/2; j+=2)
            {
                nv12[framesize + j] = nv21[j+framesize-1];
            }
        }

        //NV12转NV21互换
        public static void NV12ToNV21(byte[] nv21,byte[] nv12,int width,int height){
            if(nv21 == null || nv12 == null)return;
            int framesize = width*height;
            int i,j;
            System.arraycopy(nv12, 0, nv21, 0, framesize);
            for(i = 0; i < framesize; i++){
                nv21[i] = nv12[i];
            }
            //j从1开始才对
            for (j = 1; j < framesize/2; j+=2)
            {
                nv21[framesize + j-1] = nv12[j+framesize];
            }
            for (j = 1; j < framesize/2; j+=2)
            {
                nv21[framesize + j] = nv12[j+framesize-1];
            }
        }

        //nv21转420p
        public static int NV21_TO_yuv420P(byte[] dst, byte[] src, int w, int h)
        {
            int ysize = w * h;
            int usize = w * h * 1 / 4;

            byte[] dsttmp = dst;

            // y
            System.arraycopy(src, 0, dst, 0, ysize);

            // u, 1/4
            int srcPointer = ysize;
            int dstPointer = ysize;
            int count = usize;
            while (count > 0)
            {
                srcPointer++;
                dst[dstPointer] = src[srcPointer];
                dstPointer++;
                srcPointer++;
                count--;
            }

            // v, 1/4
            srcPointer = ysize;

            count = usize;
            while (count > 0)
            {
                dst[dstPointer] = src[srcPointer];
                dstPointer++;
                srcPointer += 2;
                count--;
            }

            dst = dsttmp;

            // _EF_TIME_DEBUG_END(0x000414141);

            return 0;
        }
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
         * 调用camera2helper对应的活动
         */
        private Activity activity;

        /**
         * 额外的旋转角度（用于适配一些定制设备）
         * 注意额外角度最终会补正到90度倍数
         */
        private int additionalRotation;

        public Builder() {
        }


        public Camera2Helper.Builder previewOn(View val) {
            //判断是不是SurfaceView或者TextureView实例
            if (val instanceof SurfaceView || val instanceof TextureView) {
                previewDisplayView = val;
                return this;
            } else {
                throw new RuntimeException("you must preview on a textureView or a surfaceView");
            }
        }


        public Camera2Helper.Builder isMirror(boolean val) {
            isMirror = val;
            return this;
        }

        public Camera2Helper.Builder previewSize(Point val) {
            previewSize = val;
            return this;
        }

        //设置预览大小
        public Camera2Helper.Builder previewViewSize(Point val) {
            previewViewSize = val;
            return this;
        }

        public Camera2Helper.Builder rotation(int val) {
            rotation = val;
            return this;
        }

        public Camera2Helper.Builder additionalRotation(int val) {
            additionalRotation = val;
            return this;
        }

        public Camera2Helper.Builder specificCameraId(Integer val) {
            specificCameraId = val;
            return this;
        }

        public Camera2Helper.Builder activityBelong(Activity val) {
            activity = val;
            return this;
        }

        public Camera2Helper.Builder cameraListener(CameraListener val) {
            cameraListener = val;
            return this;
        }

        //创建Camera2Helper实例
        public Camera2Helper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default previewSize");
            }
            if (cameraListener == null) {
                Log.e(TAG, "cameraListener is null, callback will not be called");
            }
            if (previewDisplayView == null) {
                throw new RuntimeException("you must preview on a textureView or a surfaceView");
            }
            return new Camera2Helper(this);
        }
    }

}
