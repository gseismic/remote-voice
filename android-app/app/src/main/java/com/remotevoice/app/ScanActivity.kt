package com.remotevoice.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 扫码配对页（设计 docs/design/qr-pairing-20260919-overview.md §4.2）：
 * Camera2 预览 + ImageReader(YUV) 帧 → zxing 解码（仅 QR，~8fps 节流），
 * 识别到配对码即回传给调用方（MainActivity 落地添加/连接）。
 * 「手动输入配对码」兜底：相机糊/暗/无权限场景，与扫码共用同一回传通道。
 */
class ScanActivity : Activity() {

    private lateinit var preview: SurfaceView
    private lateinit var scanStatus: TextView
    private lateinit var editPayload: EditText

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val reader = MultiFormatReader()
    /** 解码任务在后台串行执行；繁忙时丢帧，避免全帧解码烧 CPU（设计 §4.2）。 */
    @Volatile private var decoding = false
    private var lastDecodeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        preview = findViewById(R.id.preview)
        scanStatus = findViewById(R.id.scan_status)
        editPayload = findViewById(R.id.edit_payload)

        findViewById<Button>(R.id.btn_parse_payload).setOnClickListener {
            submitPayload(editPayload.text.toString())
        }
        findViewById<Button>(R.id.btn_scan_cancel).setOnClickListener { finish() }

        reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)))

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCamera()
            }
        })

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreviewWhenReady()
        } else {
            scanStatus.text = "需要相机权限才能扫码"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startPreviewWhenReady()
        } else {
            scanStatus.text = "没有相机权限：可手动输入配对码"
            Toast.makeText(this, R.string.err_no_camera_perm, Toast.LENGTH_LONG).show()
        }
    }

    /** 预览面就绪后启动相机；重复调用安全。 */
    private fun startPreviewWhenReady() {
        if (camera != null) return
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                openCamera(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                closeCamera()
            }
        })
        if (preview.holder.getSurface() != null && preview.holder.getSurface().isValid) {
            openCamera(preview.holder)
        }
    }

    private fun pickCamera(): Pair<String, Int>? {
        val manager = getSystemService(CameraManager::class.java)
        for ((id, chars) in manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }) {
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                return id to orientation
            }
        }
        val first = manager.cameraIdList.firstOrNull() ?: return null
        return first to (manager.getCameraCharacteristics(first)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(holder: SurfaceHolder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val (cameraId, sensorOrientation) = pickCamera() ?: run {
            scanStatus.text = "找不到可用相机：可手动输入配对码"
            return
        }
        val manager = getSystemService(CameraManager::class.java)
        // 解码源固定 640×480：帧小、CPU 解码开销可控（设计 §4.2）
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ onImageAvailable() }, cameraHandler ?: newCameraHandler())
        }
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    createSession(device, holder, sensorOrientation)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device) camera = null
                    scanStatus.text = "相机启动失败（错误码 $error）：可手动输入配对码"
                }
            }, cameraHandler ?: newCameraHandler())
        } catch (e: SecurityException) {
            scanStatus.text = "没有相机权限：可手动输入配对码"
        }
    }

    private fun newCameraHandler(): Handler {
        if (cameraThread == null) {
            cameraThread = HandlerThread("scan-camera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        return cameraHandler!!
    }

    private fun createSession(device: CameraDevice, holder: SurfaceHolder, sensorOrientation: Int) {
        val readerSurface = imageReader!!.surface
        holder.setFixedSize(PREVIEW_W, PREVIEW_H)
        try {
            device.createCaptureSession(
                listOf(holder.surface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (camera !== device) return
                        session = s
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(holder.surface)
                        builder.addTarget(readerSurface)
                        builder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        s.setRepeatingRequest(builder.build(), null, cameraHandler)
                        scanStatus.visibility = View.INVISIBLE
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        scanStatus.text = "相机会话创建失败：可手动输入配对码"
                    }
                },
                cameraHandler ?: newCameraHandler(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "createSession failed", e)
            scanStatus.text = "相机启动异常：可手动输入配对码"
        }
        this.sensorOrientation = sensorOrientation
    }

    private var sensorOrientation = 90

    private fun onImageAvailable() {
        val now = System.currentTimeMillis()
        if (decoding || now - lastDecodeAt < DECODE_INTERVAL_MS) {
            return
        }
        val image = imageReader?.acquireLatestImage() ?: return
        lastDecodeAt = now
        decoding = true
        val luminance = try {
            buildLuminanceSource(image, sensorOrientation)
        } catch (e: Exception) {
            Log.w(TAG, "build luminance failed", e)
            null
        } finally {
            image.close()
        }
        if (luminance == null) {
            decoding = false
            return
        }
        // 解码放后台（此处已是 camera 线程），结果回主线程
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(luminance))
            val result = try {
                reader.decodeWithState(bitmap)
            } catch (_: Exception) {
                null // 本帧无 QR，正常情况
            } finally {
                reader.reset()
            }
            result?.text?.let { text ->
                runOnUiThread { submitPayload(text) }
            }
        } finally {
            decoding = false
        }
    }

    /** YUV 平面亮度提取（处理 rowStride）+ 按传感器方向旋转。 */
    private fun buildLuminanceSource(image: Image, rotation: Int): PlanarYUVLuminanceSource {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val yBytes = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(yBytes)
        } else {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(yBytes, row * width, width)
            }
        }
        buffer.rewind()

        var data = yBytes
        var w = width
        var h = height
        when (rotation % 360) {
            90 -> {
                data = rotate90(data, width, height)
                w = height
                h = width
            }
            270 -> {
                data = rotate270(data, width, height)
                w = height
                h = width
            }
            180 -> data = rotate180(data, width, height)
        }
        // 竖屏锁定下预览为竖构图，送 zxing 前统一再转 90° 使长边为高（其坐标以 width<height 假设最佳）
        return PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
    }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            out[x * h + (h - 1 - y)] = src[y * w + x]
        }
        return out
    }

    private fun rotate270(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            out[(w - 1 - x) * h + y] = src[y * w + x]
        }
        return out
    }

    private fun rotate180(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) out[i] = src[src.size - 1 - i]
        return out
    }

    /** 校验并回传配对码给调用方（添加/连接落地在 MainActivity，设计 §4.3）。 */
    private fun submitPayload(raw: String) {
        val payload = raw.trim()
        if (ConfigParser.parsePairing(payload) == null) {
            Toast.makeText(this, "不是有效的配对码（应为 rv://host:端口?s=密码）", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, payload))
        finish()
    }

    private fun closeCamera() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            camera?.close()
        } catch (_: Exception) {
        }
        session = null
        camera = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAYLOAD = "payload"
        private const val REQ_CAMERA = 11
        private const val TAG = "ScanActivity"
        private const val PREVIEW_W = 640
        private const val PREVIEW_H = 480
        private const val DECODE_INTERVAL_MS = 125L
    }
}
