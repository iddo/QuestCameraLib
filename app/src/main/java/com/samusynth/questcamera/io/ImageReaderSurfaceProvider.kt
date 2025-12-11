package com.samusynth.questcamera.io

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.samusynth.questcamera.core.ISurfaceProvider
import com.samusynth.questcamera.json.toJson
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ImageReaderSurfaceProvider(
    private val width: Int,
    private val height: Int,
    imageFileDirPath: String,
    formatInfoFilePath: String,
    private val bufferPoolSize: Int = 5,
    private val useQoiCompression: Boolean = false  // Disabled: real-time compression causes stuttering on Quest hardware
): ISurfaceProvider, AutoCloseable {
    private val handlerThread = HandlerThread("ImageReaderBackground").apply {
        start()
    }
    private val backgroundHandler = Handler(handlerThread.looper)

    private var directory: File = File(imageFileDirPath)
    private var formatInfoFilePath: String = formatInfoFilePath

    private var imageFormatInfo: ImageFormatInfo? = null

    // One-shot flag: capture next available frame
    // AtomicBoolean ensures thread-safe compare-and-set operation (prevents race conditions)
    private val shouldCaptureNextFrame = AtomicBoolean(false)
    
    // Use lower priority thread to prevent stealing CPU from render thread
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable).apply {
            name = "ImageSaver"
            priority = Thread.MIN_PRIORITY  // Don't compete with render thread
        }
    }

    private val bufferPool = MutableList(bufferPoolSize) { ByteArray(width * height + width * height / 2) }
    private var latestBufferPoolIndex = 0

    private var baseMonoTimeNs: Long
    private var baseUnixTimeMs: Long

    private val imageReader = ImageReader.newInstance(
        width,
        height,
        ImageFormat.YUV_420_888,
        2
    ).apply {
        setOnImageAvailableListener({ reader ->
            // Use acquireLatestImage() to get the most recent frame, not the oldest
            // This prevents capturing old buffered frames when capture starts
            reader.acquireLatestImage()?.use { image ->
                processImage(image)
            }
        }, backgroundHandler)
    }

    init {
        if (!directory.exists()) {
            val created = directory.mkdirs()
            if (!created) {
                Log.e(TAG, "Failed to create image file directory.")
            }
        }

        baseMonoTimeNs = System.nanoTime()
        baseUnixTimeMs = System.currentTimeMillis()

        Log.d(TAG, "[Time Log] Base Mono Time (ns): $baseMonoTimeNs, Base Unix Time (ms): $baseUnixTimeMs")
    }

    override fun getSurface(): Surface {
        return imageReader.surface
    }

    /**
     * Resets the base time for timestamp calculations.
     * Called when recording starts to sync with depth system's base time.
     */
    fun resetBaseTime() {
        baseMonoTimeNs = System.nanoTime()
        baseUnixTimeMs = System.currentTimeMillis()
        Log.d(TAG, "Base time reset: baseMonoTimeNs=$baseMonoTimeNs, baseUnixTimeMs=$baseUnixTimeMs")
    }

    /**
     * Updates the output directory paths for a new recording session.
     * Called when starting a new recording session with a new timestamped directory.
     * Also resets format info cache to ensure it's written to the new location.
     */
    fun updateDirectoryPaths(newImageDir: String, newFormatInfoPath: String) {
        directory = File(newImageDir)
        formatInfoFilePath = newFormatInfoPath
        imageFormatInfo = null  // Force re-write to new location
        
        // Create directory if it doesn't exist
        if (!directory.exists()) {
            directory.mkdirs()
        }
        
        Log.d(TAG, "Updated paths: imageDir=$newImageDir, formatInfo=$newFormatInfoPath")
    }

    /**
     * Signals the camera to capture the next available frame.
     * Called by Unity when CaptureCoordinator determines a frame should be captured.
     * Flag auto-resets after capture.
     */
    fun captureNextFrame() {
        shouldCaptureNextFrame.set(true)
        Log.d(TAG, "Capture signal received - will save next frame")
    }

    fun getLatestImageBytes(): ByteArray {
        val byteArray = bufferPool[latestBufferPoolIndex].copyOf()
        return nv12ByteArrayToJpeg(byteArray, width = width, height = height)
    }

    override fun close() {
        imageReader.close()
        saveExecutor.shutdown()
    }

    private fun processImage(image: Image) {
        // Atomic compare-and-set: only proceed if flag is true, and atomically set it to false
        // This prevents race conditions where multiple frames might see the flag as true
        // compareAndSet returns true only for the FIRST frame that sees true
        if (!shouldCaptureNextFrame.compareAndSet(true, false)) {
            return  // Either flag was false, or another thread already captured
        }

        Log.d(TAG, "Capturing frame in response to signal")

        if (imageFormatInfo == null) {
            val baseTime = BaseTime(
                baseMonoTimeNs = baseMonoTimeNs,
                baseUnixTimeMs = baseUnixTimeMs,
            )
            val info = extractImageFormatInfo(image, baseTime = baseTime)
            imageFormatInfo = info

            // Save format info on first capture
                val formatInfoFIle = File(formatInfoFilePath)
                saveExecutor.execute {
                    try {
                        formatInfoFIle.bufferedWriter().use { it.write(info.toJson()) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                }
            }
        }

        val nextBufferPoolIndex = (latestBufferPoolIndex + 1) % bufferPoolSize
        val buffer = bufferPool[nextBufferPoolIndex]

        // Copy image data into our buffer pool (fast operation, ~5-10ms)
        val data = dumpImageUnsafe(image, buffer)
        bufferPool[nextBufferPoolIndex] = data

        latestBufferPoolIndex = nextBufferPoolIndex

        // Save image data (we only reach here if capture was signaled)
        val timestamp = computeUnixTime(image.timestamp)
        
        // Move all CPU-heavy work to low-priority background thread
        saveExecutor.execute {
            // Set low priority for this specific task to minimize impact on render thread
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (useQoiCompression) {
                    // Convert YUV to RGB
                    val startTime = System.currentTimeMillis()
                    val rgb = convertYuv420ToRgb(data, width, height)
                    val conversionTime = System.currentTimeMillis() - startTime
                    
                    // Encode to QOI format
                    val encodeStartTime = System.currentTimeMillis()
                    val qoiData = QoiEncoder.encodeRgb(rgb, width, height)
                    val encodeTime = System.currentTimeMillis() - encodeStartTime
                    
                    // Write to file
                    val fileName = "$timestamp.qoi"
                    val file = File(directory, fileName)
                    BufferedOutputStream(FileOutputStream(file)).use {
                        it.write(qoiData)
                    }
                    
                    val qoiSize = file.length()
                    val compressionRatio = 100.0 * qoiSize / data.size
                    Log.d(TAG, "Saved QOI: ${file.name}, " +
                          "YUV: ${data.size} bytes, QOI: $qoiSize bytes, " +
                          "ratio: ${String.format("%.1f", compressionRatio)}%, " +
                          "conversion: ${conversionTime}ms, encode: ${encodeTime}ms")
                } else {
                    // Original YUV path (for compatibility/debugging)
                    val fileName = "$timestamp.yuv"
                    val file = File(directory, fileName)
                    BufferedOutputStream(FileOutputStream(file)).use { 
                        it.write(data) 
                    }
                    Log.d(TAG, "Saved YUV: ${file.name}, size: ${data.size} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save image: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun computeUnixTime(imageTimestamp: Long): Long {
        val deltaNs = imageTimestamp - baseMonoTimeNs
        return baseUnixTimeMs + (deltaNs / 1_000_000)
    }
    
    /**
     * Convert YUV420 (NV12 format from Android Camera2) to RGB for QOI encoding.
     * Uses ITU-R BT.601 conversion matrix with optimized fixed-point integer math.
     * 
     * Performance: ~2-3x faster than floating-point version by using integer arithmetic.
     */
    private fun convertYuv420ToRgb(yuv: ByteArray, width: Int, height: Int): ByteArray {
        val rgb = ByteArray(width * height * 3)
        val frameSize = width * height
        val uvStart = frameSize
        
        // Fixed-point conversion constants (scaled by 256 for integer math)
        // Original float constants * 256:
        // 1.164 * 256 = 298
        // 1.596 * 256 = 409
        // 0.392 * 256 = 100
        // 0.813 * 256 = 208
        // 2.017 * 256 = 516
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val yIndex = j * width + i
                val uvIndex = uvStart + (j shr 1) * width + (i and 1.inv())
                
                // Extract YUV values (already expanded to int range)
                val y = (yuv[yIndex].toInt() and 0xff) - 16
                val u = (yuv[uvIndex].toInt() and 0xff) - 128
                val v = (yuv[uvIndex + 1].toInt() and 0xff) - 128
                
                // ITU-R BT.601 conversion using fixed-point integer math
                // Divide by 256 (>> 8) to convert back from fixed-point
                val yScaled = 298 * y
                val r = ((yScaled + 409 * v) shr 8).coerceIn(0, 255)
                val g = ((yScaled - 100 * u - 208 * v) shr 8).coerceIn(0, 255)
                val b = ((yScaled + 516 * u) shr 8).coerceIn(0, 255)
                
                // Write RGB values
                val rgbIndex = yIndex * 3
                rgb[rgbIndex] = r.toByte()
                rgb[rgbIndex + 1] = g.toByte()
                rgb[rgbIndex + 2] = b.toByte()
            }
        }
        
        return rgb
    }

    companion object {
        private val TAG = ImageReaderSurfaceProvider::class.java.simpleName

        fun dumpImageUnsafe(image: Image, reusedBuffer: ByteArray): ByteArray {
            val requiredSize = calculateDumpBufferSize(image)

            val outputBuffer = if (reusedBuffer.size >= requiredSize) {
                reusedBuffer
            } else {
                ByteArray(requiredSize)
            }

            var offset = 0
            for (plane in image.planes) {
                val buffer = plane.buffer
                buffer.position(0)
                val size = buffer.remaining()
                buffer.get(outputBuffer, offset, size)
                offset += size
            }
            return outputBuffer
        }

        private fun calculateDumpBufferSize(image: Image): Int {
            return image.planes.sumOf { it.buffer.remaining() }
        }

        private fun convertNV12ToNV21(nv12: ByteArray, width: Int, height: Int): ByteArray {
            val ySize = width * height
            val uvSize = ySize / 2
            val nv21 = nv12.copyOf()

            var i = ySize
            while (i < ySize + uvSize - 1) {
                val u = nv12[i]
                nv21[i] = nv12[i + 1]
                nv21[i + 1] = u
                i += 2
            }

            return nv21
        }

        private fun nv12ByteArrayToJpeg(nv12: ByteArray, width: Int, height: Int): ByteArray {
            val nv21 = convertNV12ToNV21(nv12, width, height)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val output = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, output)
            return output.toByteArray()
        }

    }
}