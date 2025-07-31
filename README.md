# ArcfaceDemo_support_AndroidCamera2
Add support for Android Camera2 based on ArcSoft 3.0 Demo  


Change Logs:  
2020-09-18  
1. Added `com.arcsoft.arcfacedemo.util.camera.Camera2Helper` based on the original ArcFaceDemo.  
2. Modified `com.arcsoft.arcfacedemo.activity.FaceAttrPreviewActivity` (for face attribute detection in video), changing from using `cameraHelper` to `camera2Helper`.  
3. Other functions in the demo remain unmodified for now, but you can refer to `FaceAttrPreviewActivity` for switching between `camera1` and `camera2` usage.
