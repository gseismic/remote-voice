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
import android.os.Looper
import android.os.SystemClock
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
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

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

    /** 每次打开独占自己的相机、会话和帧。生命周期在主线程，解码器仅由工作线程使用。 */
    private class CameraPreview(
        val surface: Surface,
        val orientation: Int,
        val continuousFocus: Boolean,
    ) {
        val frames: ImageReader = ImageReader.newInstance(PREVIEW_W, PREVIEW_H, ImageFormat.YUV_420_888, 3)
        val worker = HandlerThread("scan-decode").apply { start() }
        val decodeHandler = Handler(worker.looper)
        val decoder = QRCodeReader()
        var lastDecodeAt = -DECODE_INTERVAL_MS
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var currentPreview: CameraPreview? = null

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

        preview.holder.setFixedSize(PREVIEW_W, PREVIEW_H)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = openCamera(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = closeCamera()
        })

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showCameraStatus("需要相机权限才能扫码")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        openCamera(preview.holder)
    }

    override fun onPause() {
        resumed = false
        closeCamera()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera(preview.holder)
        } else {
            showCameraStatus("没有相机权限：可手动输入配对码")
            Toast.makeText(this, R.string.err_no_camera_perm, Toast.LENGTH_LONG).show()
        }
    }

    private fun showCameraStatus(message: String) {
        scanStatus.text = message
        scanStatus.visibility = View.VISIBLE
    }

    private fun pickCamera(manager: CameraManager): Pair<String, CameraCharacteristics>? {
        val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
        return cameras.firstOrNull { (_, characteristics) ->
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameras.firstOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(holder: SurfaceHolder) {
        if (!resumed || isFinishing || currentPreview != null || !holder.surface.isValid) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        showCameraStatus("正在启动相机…")
        try {
            val manager = getSystemService(CameraManager::class.java)
            val (cameraId, characteristics) = pickCamera(manager) ?: run {
                showCameraStatus("找不到可用相机：可手动输入配对码")
                return
            }
            val opening = CameraPreview(
                holder.surface,
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true,
            )
            currentPreview = opening
            opening.frames.setOnImageAvailableListener({ decodeFrame(opening) }, opening.decodeHandler)
            manager.openCamera(cameraId, cameraCallback(opening), mainHandler)
        } catch (e: SecurityException) {
            closeCamera()
            showCameraStatus("没有相机权限：可手动输入配对码")
        } catch (e: Exception) {
            Log.w(TAG, "打开相机失败", e)
            closeCamera()
            showCameraStatus("无法打开相机：可手动输入配对码")
        }
    }

    private fun cameraCallback(opening: CameraPreview) = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (currentPreview !== opening) {
                device.close()
                return
            }
            opening.device = device
            createSession(opening, device)
        }

        override fun onDisconnected(device: CameraDevice) {
            closeUnavailableCamera(device, "相机已断开：可手动输入配对码")
        }

        override fun onError(device: CameraDevice, error: Int) {
            closeUnavailableCamera(device, "相机启动失败（错误码 $error）：可手动输入配对码")
        }

        private fun closeUnavailableCamera(device: CameraDevice, message: String) {
            if (currentPreview === opening) {
                opening.device = device
                failCamera(opening, message)
            } else {
                device.close()
            }
        }
    }

    private fun createSession(opening: CameraPreview, device: CameraDevice) {
        try {
            check(opening.surface.isValid)
            val frames = opening.frames.surface
            device.createCaptureSession(listOf(opening.surface, frames), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (currentPreview !== opening) {
                        session.close()
                        return
                    }
                    opening.session = session
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(opening.surface)
                        builder.addTarget(frames)
                        if (opening.continuousFocus) {
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        session.setRepeatingRequest(builder.build(), null, null)
                        scanStatus.visibility = View.INVISIBLE
                    } catch (e: Exception) {
                        Log.w(TAG, "启动相机预览失败", e)
                        failCamera(opening, "无法预览相机：可手动输入配对码")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    failCamera(opening, "相机会话创建失败：可手动输入配对码")
                }
            }, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "创建相机会话失败", e)
            failCamera(opening, "相机启动异常：可手动输入配对码")
        }
    }

    private fun failCamera(opening: CameraPreview, message: String) {
        if (currentPreview !== opening) return
        closeCamera()
        showCameraStatus(message)
    }

    private fun decodeFrame(opening: CameraPreview) {
        val image = try {
            opening.frames.acquireLatestImage()
        } catch (_: IllegalStateException) {
            return // 关闭后的迟到图像回调
        } ?: return
        val luminance = image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - opening.lastDecodeAt < DECODE_INTERVAL_MS) return
            opening.lastDecodeAt = now
            try {
                buildLuminanceSource(image, opening.orientation)
            } catch (e: RuntimeException) {
                Log.w(TAG, "读取相机图像失败", e)
                return
            }
        }
        val payload = try {
            opening.decoder.decode(BinaryBitmap(HybridBinarizer(luminance))).text
        } catch (_: ReaderException) {
            return
        }
        mainHandler.post {
            if (currentPreview === opening) submitPayload(payload)
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
        // 将按传感器方向旋转后的亮度平面交给 ZXing。
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
        if (isFinishing) return
        val payload = raw.trim()
        if (ConfigParser.parsePairing(payload) == null) {
            Toast.makeText(this, "配对码无效，请扫描 Mac 上的配对二维码", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, payload))
        finish()
    }

    private fun closeCamera() {
        val closing = currentPreview ?: return
        currentPreview = null
        runCatching { closing.session?.close() }.onFailure { Log.w(TAG, "关闭相机会话失败", it) }
        runCatching { closing.device?.close() }.onFailure { Log.w(TAG, "关闭相机失败", it) }
        closing.frames.setOnImageAvailableListener(null, null)
        // 图像读写与释放排在同一线程，保证解码期间 ImageReader 仍然有效。
        closing.decodeHandler.post { closing.frames.close() }
        closing.worker.quitSafely()
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
