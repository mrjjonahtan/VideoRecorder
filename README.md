# VideoRecorde
工程：Android studio 工程

语言：kotlin 和 java

功能：录像 存mp4 拍照 存jpg

说明：工程中尽可能的注释了每行，工程可基本满足要求，有一些错误还没更改，其中就有点击停止录屏之后就不能用了，等等一些bug。

大致流程如下：

    （初始化）

CameraManager -> getCameraIdList -> openCamera(,CameraDevice.StateCallback,) -> CameraDevice -> createCaptureSession(,CameraCaptureSession.StateCallback,) -> CameraCaptureSession -> setRepeatingRequest

>>在初始化时首先请求相关权限，然后获得屏幕参数，设置镜头参数等一些初始化工作。

之后根据getSystemService(Context.CAMERA_SERVICE)获得CameraManager，使用CameraManager.getCameraIdList获得摄像头列表和摄像头参数，

使用CameraManager.openCamera打开摄像头，使用openCamera时可以设置回调函数CameraDevice.StateCallback来获取打开状态，

如果正确打开则会执行回掉函数中onOpened方法，即可获得CameraDevice，然后使用CameraDevice.createCaptureSession

之前要使用CameraDevice.createCaptureRequest设置请求参数，之后需要创建CameraCaptureSession.StateCallback回掉来获得请求的结果

请求成功时则会执行回调函数中onConfigured方法，在onConfigured方法中可获得CameraCaptureSession，之后设置预览参数，

使用CameraCaptureSession.setRepeatingRequest请求预览。

    （拍照）

CameraCaptureSession.capture(,CameraCaptureSession.CaptureCallback(),) -> TotalCaptureResult

>>工程中初始化之后的拍照工作大致为请求拍照，对焦，拍摄静态图片，解锁对焦。其中使用PreviewRequestBuilder设置请求参数，

使用CameraCaptureSession.capture提交请求，请求成功会回调函数onCaptureCompleted，在onCaptureCompleted中获得

CaptureResult，使用CaptureResult获得对焦状态，对焦成功，设置参数使用capture请求拍照，函数回掉onCaptureCompleted

时解除对焦即可。

    （录像）

MediaRecorder -> CameraDevice.createCaptureSession -> CameraCaptureSession.setRepeatingRequest

>>录像首先创建MediaRecorder并设置录像时所需要参数，有一些参数有顺序问题需要格外注意。 然后使用

CameraDevice.createCaptureSession设置CameraCaptureSession.StateCallback会回调函数，

在CameraCaptureSession.StateCallback回调函数中可获得CameraCaptureSession，使用setRepeatingRequest请求设置

照相机模式，然后MediaRecorder.start()开始录像。


（工程中会有错误的地方，会不断更新...）

参考资料：
https://www.jianshu.com/p/73fed068a795

https://blog.csdn.net/Lyman_Ye/article/details/78897819