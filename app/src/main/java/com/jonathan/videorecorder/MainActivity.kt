package com.jonathan.videorecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList


/**
 * CameraManager：摄像头管理器。专门用于检测系统摄像头、打开系统摄像头。
 * 除此之外，调用CameraManager的getCameraCharacteristics(String)方法即可获取指定摄像头的相关特性。
 *
 * CameraCharacteristics：摄像头特性。该对象通过CameraManager来获取，用于描述特定摄像头所支持的各种特性。
 *
 * CameraDevice：代表系统摄像头。该类的功能类似于早期的Camera类。
 *
 * CameraCaptureSession：这是一个非常重要的API，当程序需要预览、拍照时，都需要先通过该类的实例创建Session。
 * 而且不管预览还是拍照，也都是由该对象的方法进行控制的，其中控制预览的方法为setRepeatingRequest()；控制拍照的方法为capture()。
 *
 * CameraRequest和CameraRequest.Builder：当程序调用setRepeatingRequest()方法进行预览时，或调用capture()方法进行拍照时，
 * 都需要传入CameraRequest参数。CameraRequest代表了一次捕获请求，用于描述捕获图片的各种参数设置，
 * 比如对焦模式、曝光模式……总之，程序需要对照片所做的各种控制，都通过CameraRequest参数进行设置。
 * CameraRequest.Builder则负责生成CameraRequest对象。
 *
 * MediaStore.ACTION_IMAGE_CAPTURE 拍摄照片；
 * MediaStore.ACTION_VIDEO_CAPTURE 拍摄视频；
 */

class MainActivity : AppCompatActivity(), View.OnClickListener, SurfaceHolder.Callback {
    val Tag: String = "MainActivity"
    val MY_PERMISSIONS_REQUEST_READ_CONTACTS: Int = 1

    //camera preview
    val CAMERA_FRONT: String = CameraCharacteristics.LENS_FACING_FRONT.toString()          //前置
    val CAMERA_BACK: String = CameraCharacteristics.LENS_FACING_BACK.toString()            //后置

    val STATE_WAITING_LOCK: Int = 1                                         //对焦状态
    val STATE_PREVIEW: Int = 2                                              //预览状态
    val STATE_PICTURE_TAKEN: Int = 3                                        //准备拍照
    val STATE_VIDEO: Int = 4                                                 //录视频

    var mState: Int = 0                                                     //状态
    var isCamera: Boolean = false
    var isPermission: Boolean = false

    var width: Int = 0                                                      //屏幕宽
    var height: Int = 0                                                     //屏幕高

    var mSurfaceHolder: SurfaceHolder? = null

    var handler: Handler? = null                                             //一个线程
    var cameraManager: CameraManager? = null                                 //摄像头管理器。
    var cameraList: Array<String>? = null                                    //摄像头列表
    var cameraDevice: CameraDevice? = null                                   //摄像头
    var usingCameraId: String = ""                                           //正在使用的摄像头id
    var mCaptureSession: CameraCaptureSession? = null
    var mPreviewRequestBuilder: CaptureRequest.Builder? = null
    var mPreviewRequest: CaptureRequest? = null
    var imageReader: ImageReader? = null                                     //JPEG图像

    var imageFilePath: String? = null                                        //图片文件路径

    //video record
    val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

    var DEFAULT_ORIENTATIONS: SparseIntArray = SparseIntArray()
    var INVERSE_ORIENTATIONS: SparseIntArray = SparseIntArray()

    var mMediaRecorder: MediaRecorder? = null
    var mVideoSize: Size? = null                                             //视频尺寸
    var mSensorOrientation: Int? = null                                      //传感器方向


    //相机状态进行监听（回调函数）
    var stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            //获得摄像头
            cameraDevice = camera
            //开启预览
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice?) {
            cameraDevice!!.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            cameraDevice!!.close()
            cameraDevice = null
        }

    }

    //预览回掉
    var sessioncall = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession?) {
            Log.e(Tag, " onConfigureFailed 开启预览失败")
        }

        override fun onConfigured(session: CameraCaptureSession?) {
            // 相机已经关闭
            if (null == cameraDevice) {
                return
            }
            // 会话准备好后，我们开始显示预览
            mCaptureSession = session

            try {

                // 自动对焦应
                mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                // 开启相机预览并添加事件
                mPreviewRequest = mPreviewRequestBuilder!!.build()
                //发送请求
                mCaptureSession!!.setRepeatingRequest(mPreviewRequest, null, handler)
                //预览状态
                mState = STATE_PREVIEW

            } catch (e: Exception) {
                Log.e(Tag, e.message)
            }

        }

    }

    /*静止图像已准备好保存。*/
    var monImageAvailableListener = object : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            //当图片可得到的时候获取图片并保存
            handler!!.post(ImageSave(reader!!.acquireNextImage(), imageFilePath!!))
        }

    }

    //拍照回掉 当请求触发捕获开始以及捕获完成时，将调用此回调
    var captureCallback = object : CameraCaptureSession.CaptureCallback() {

        //当图像捕获完全完成并且所有结果元数据都可用时，将调用此方法。
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)
            if (result != null) {
                process(result)
            }
        }

        /**
         * 处理与JPEG捕获有关的事件
         */

        fun process(result: CaptureResult) {
            when (mState) {
                STATE_WAITING_LOCK -> {

                    //等待对焦
                    var afState = result.get(CaptureResult.CONTROL_AF_STATE)

                    if (afState == null || afState == 0) {

                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {

                        // CONTROL_AE_STATE 状态在某些设备上可以为空
                        var aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == 0 || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN
                            //对焦完成
                            captureStillPicture()
                        } else {
                            //runPrecaptureSequence()
                            Log.e(Tag, "-=-=-=-=-==-=-=-=-=-=-")
                        }

                    }


                }

            }

        }

    }


    override fun onClick(v: View?) {
        when (v) {
            btn_videotape -> {
                if (!isPermission) {
                    text_hint.text = "先初始化哟"
                    return
                }
                text_hint.text = "录制中..."
                startRecordingVideo()
            }
            btn_stop -> {
                if (!isPermission) {
                    text_hint.text = "先初始化哟"
                    return
                }
                text_hint.text = "录制已停止."
                stopRecordingVideo()
            }
            btn_cam -> {
                if (!isPermission) {
                    text_hint.text = "先初始化哟"
                    return
                }
                //拍照
                takePicture()
            }
            btn_init -> {
                if (!isPermission) {
                    checkPermisson()
                    isPermission = true
                }
                text_hint.text = "初始化完成"
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        actinit()
    }


    fun actinit() {

        btn_videotape.setOnClickListener(this)
        btn_stop.setOnClickListener(this)
        btn_init.setOnClickListener(this)
        btn_cam.setOnClickListener(this)

        mSurfaceHolder = surfaceView.holder
        mSurfaceHolder!!.setFormat(PixelFormat.TRANSPARENT)
        mSurfaceHolder!!.setKeepScreenOn(true)
        mSurfaceHolder!!.addCallback(this)
    }

    fun mainfun() {
        initfun()
        checkCameraService()
        getDefaultCameraId(isCamera)
        openCamerafun()
    }

    fun initfun() {
        getScreenBaseInfo()
        imageFilePath = getDiskCacheDir(this)               //文件路径

        //角度
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270)
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180)

        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)

    }

    fun checkCameraService() {
        // Check if device policy has disabled the camera.
        val dpm: DevicePolicyManager = this.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.getCameraDisabled(null)) {
            isCamera = true
        }
    }

    //获得摄像头
    fun getDefaultCameraId(isCam: Boolean) {
        if (!isCam) {
            return
        }
        //获得CameraManager
        cameraManager = this.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        //初始化image
        initImageMessage()

        try {
            //获得摄像头列表
            cameraList = cameraManager!!.getCameraIdList()
            for (i in cameraList!!.indices) {
                val cameraId = cameraList!![i]

                if (TextUtils.equals(cameraId, CAMERA_FRONT)) {
                    usingCameraId = cameraId
                    //获得摄像头相关信息
                    initCameraMessage(cameraManager!!, usingCameraId)
                    break
                } else if (TextUtils.equals(cameraId, CAMERA_BACK)) {
                    usingCameraId = cameraId
                    initCameraMessage(cameraManager!!, usingCameraId)
                    break
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(Tag, e.message)
            e.printStackTrace()
        }

    }

    /**
     * 获取屏幕相关数据
     */
    private fun getScreenBaseInfo() {

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        width = metrics.widthPixels
        height = metrics.heightPixels
        //mScreenDensity = metrics.densityDpi
    }

    //初始化图片添加ImageAvailableListener事件，点击拍照时回掉
    fun initImageMessage() {

        // 对于静态图像拍摄，使用最大的可用尺寸。
        //var largest:Size = Collections.max(arrayListOf(map.getOutputSizes(ImageFormat.JPEG)),9)

        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener(monImageAvailableListener, handler)
    }

    //获取相机的相关参数
    fun initCameraMessage(camera: CameraManager, did: String) {
        //获取相机的相关参数
        var characteristics: CameraCharacteristics = camera.getCameraCharacteristics(did)

        //传感器定向
        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

        var map: StreamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            return
        }

        //获取视频尺寸大小
        mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))

    }

    /**
     * 在这个样本中，我们选择一个具有3x4宽高比的视频大小。而且，我们不使用尺寸。
     * 大于1080P，因为MealAc录音不能处理这样的高分辨率视频。
     * @param choices The list of available sizes
     * @return The video size
     */
    private fun chooseVideoSize(choices: Array<Size>): Size {
        for (size in choices) {
            if (size.width == size.height * 4 / 3 && size.width <= 1080) {
                return size
            }
        }
        Log.e(Tag, "Couldn't find any suitable video size")
        return choices[choices.size - 1]
    }

    //请求权限
    fun checkPermisson() {

        if (Build.VERSION.SDK_INT < 23) {
            mainfun()
            return
        }

        val camerPe = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val storagePe = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val dudioPe = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        if (camerPe != PackageManager.PERMISSION_GRANTED && storagePe != PackageManager.PERMISSION_GRANTED && dudioPe != PackageManager.PERMISSION_GRANTED) {
            mainfun()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO), MY_PERMISSIONS_REQUEST_READ_CONTACTS)
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_READ_CONTACTS -> {
                mainfun()
            }
        }
    }

    //打开摄像头并回调
    @SuppressLint("MissingPermission")
    fun openCamerafun() {
        //线程初始化
        val handlerThread = HandlerThread("Camera2")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        try {
            // 打开相机，第一个参数指示打开哪个摄像头，第二个参数stateCallback为相机的状态回调接口，
            // 第三个参数用来确定Callback在哪个线程执行，为null的话就在当前线程执行
            cameraManager!!.openCamera(usingCameraId, stateCallback, handler)
        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }
    }

    //为相机预览创建新的CameraCaptureSession
    fun createCameraPreviewSession() {

        try {
            //设置了一个具有输出Surface的CaptureRequest.Builder。
            //CameraDevice.TEMPLATE_PREVIEW 告诉相机我们只需要预览
            //TEMPLATE_RECORD 创建适合录像的请求。
            //TEMPLATE_PREVIEW 创建一个适合于相机预览窗口的请求。
            //TEMPLATE_STILL_CAPTURE 创建适用于静态图像捕获的请求
            //TEMPLATE_VIDEO_SNAPSHOT 在录制视频时创建适合静态图像捕获的请求。
            mPreviewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mPreviewRequestBuilder!!.addTarget(mSurfaceHolder!!.surface)

            //创建一个CameraCaptureSession来进行相机预览。
            cameraDevice!!.createCaptureSession(arrayListOf(mSurfaceHolder!!.surface, imageReader!!.surface), sessioncall, handler)

        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }
    }

    //拍照
    fun takePicture() {
        lockFocus()
    }

    /**
     * 将焦点锁定为静态图像捕获的第一步。（对焦）
     */
    fun lockFocus() {
        try {
            // 相机对焦
            mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            // 修改状态
            mState = STATE_WAITING_LOCK

            //提交要由相机设备捕获的图像的请求
            mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), captureCallback, handler)

        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }
    }

    /**
     * 解锁焦点
     */
    fun unlockFocus() {
        try {
            //重置自动对焦
            mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            mCaptureSession!!.capture(mPreviewRequestBuilder!!.build(), captureCallback, handler)
            // 将相机恢复正常的预览状态。
            mState = STATE_PREVIEW
            // 打开连续取景模式
            mCaptureSession!!.setRepeatingRequest(mPreviewRequest, captureCallback, handler)

        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }
    }

    /*
    设置闪光灯
     */
    fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
    }


    //拍摄静态图片
    fun captureStillPicture() {
        try {
            if (cameraDevice == null) {
                return
            }

            var mcaptureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

            mcaptureBuilder!!.addTarget(imageReader!!.surface)

            // 使用相同的AE和AF模式作为预览。
            mcaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            //回掉
            val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d(Tag, "TakePicture success")
                    unlockFocus()
                }
            }

            //停止连续取景
            mCaptureSession!!.stopRepeating()
            //捕获图片
            mCaptureSession!!.capture(mcaptureBuilder.build(), mCaptureCallback, handler)

        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }
    }

    //文件路径
    fun getDiskCacheDir(context: Context): String {
        var cachePath: String? = null
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) || !Environment.isExternalStorageRemovable()) {
            cachePath = context.externalCacheDir!!.path
        } else {
            cachePath = context.cacheDir.path
        }
        return cachePath
    }


    //开始录视频
    fun startRecordingVideo() {

        //检查有没有获取摄像头
        if (cameraDevice == null) {
            return
        }
        try {

            //停止连续取景
            mCaptureSession!!.stopRepeating()
            //设置Recorder
            setUpMediaRecorder()
            //设置Surface
            mPreviewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            var recorderSurface = mMediaRecorder!!.surface
            var surfaces = ArrayList<Surface>()
            //surfaces.add(mSurfaceHolder!!.surface)
            surfaces.add(recorderSurface)
            //mPreviewRequestBuilder!!.addTarget(mSurfaceHolder!!.surface)
            mPreviewRequestBuilder!!.addTarget(recorderSurface)

            mState = STATE_VIDEO
            //启动捕获会话
            //会话启动后，我们可以更新UI并开始录制。
            cameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession?) {

                }

                override fun onConfigured(session: CameraCaptureSession?) {
                    // 相机已经关闭
                    if (null == cameraDevice) {
                        return
                    }
                    mCaptureSession = session

                    try {
                        //设置相机的控制模式为自动，方法具体含义点进去（auto-exposure, auto-white-balance, auto-focus）
                        mPreviewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        // 开启相机预览并添加事件 发送请求
                        mCaptureSession!!.setRepeatingRequest(mPreviewRequestBuilder!!.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e(Tag, e.message)
                    }

                    //开始录制
                    mMediaRecorder!!.start()
                }

            }, handler)

        } catch (e: Exception) {
            Log.e(Tag, e.message)
        }

    }

    //停止录制视频
    fun stopRecordingVideo() {


        try {
            if(mMediaRecorder != null){

                mMediaRecorder!!.setOnErrorListener(null)
                mMediaRecorder!!.setOnInfoListener(null)
                mMediaRecorder!!.setPreviewDisplay(null)
                // Stop recording
                mMediaRecorder!!.stop()

                mMediaRecorder!!.release()
                mMediaRecorder = null
            }
        }catch (e:Exception){
            Log.e(Tag,e.message)
        }

        //unlockFocus()
    }

    //设置视频参数
    fun setUpMediaRecorder() {
        //创建MediaRecord
        mMediaRecorder = MediaRecorder()

        //文件路径
        val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmmss")
        val date = Date(System.currentTimeMillis())
        val videoFilePath = imageFilePath + "/" + simpleDateFormat.format(date) + ".mp4"

        mMediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        // 设置录制视频源为Camera(相机)
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        // 设置录制完成后视频的封装格式THREE_GPP为3gp.MPEG_4为mp4
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        // 设置录制的视频编码h263 h264
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // 设置视频录制的分辨率。必须放在设置编码和格式的后面，否则报错
        mMediaRecorder!!.setVideoSize(mVideoSize!!.getWidth(), mVideoSize!!.getHeight())
        //mMediaRecorder!!.setVideoSize(width, height)
        // 设置录制的视频帧率。必须放在设置编码和格式的后面，否则报错
        mMediaRecorder!!.setVideoFrameRate(30)
        mMediaRecorder!!.setOutputFile(videoFilePath)
        mMediaRecorder!!.setVideoEncodingBitRate(10000000)
        //mMediaRecorder!!.setPreviewDisplay(mSurfaceHolder!!.surface)

        //屏幕旋转方向
        var rotation = this.windowManager.defaultDisplay.rotation
        when (mSensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES -> {
                mMediaRecorder!!.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            }
            SENSOR_ORIENTATION_INVERSE_DEGREES -> {
                mMediaRecorder!!.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
            }
        }
        //准备录制
        mMediaRecorder!!.prepare()

    }

    //关照相机
    fun closeCamera() {
        if (null != mCaptureSession) {
            mCaptureSession!!.close()
            mCaptureSession = null
        }

        if (null != cameraDevice) {
            cameraDevice!!.close()
            cameraDevice = null
        }

        if (null != cameraManager) {
            cameraManager!!.unregisterAvailabilityCallback(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager!!.unregisterTorchCallback(null)
            }
        }

        if (null != imageReader) {
            imageReader!!.close()
            imageReader = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {

    }

    override fun surfaceCreated(holder: SurfaceHolder?) {

    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {

    }


    //内部类存储图片
    inner class ImageSave(image: Image, filePath: String) : Runnable {

        var mImage: Image? = null
        var mFile: File? = null                          //图片文件

        init {
            mImage = image

            //创建文件
            val simpleDateFormat = SimpleDateFormat("yyyyMMddHHmmss")
            val date = Date(System.currentTimeMillis())

            mFile = File(filePath + "/" + simpleDateFormat.format(date) + ".jpg")
        }

        override fun run() {
            var buffer = mImage!!.getPlanes()[0].getBuffer()
            var bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var output: FileOutputStream? = null
            try {

                output = FileOutputStream(mFile)
                output.write(bytes)
            } catch (e: Exception) {
                Log.e(Tag, e.message)
            } finally {
                mImage!!.close()
                if (output != null) {
                    try {
                        output.close()
                    } catch (e: Exception) {
                        Log.e(Tag, e.message)
                    }
                }
            }
        }

    }

}
