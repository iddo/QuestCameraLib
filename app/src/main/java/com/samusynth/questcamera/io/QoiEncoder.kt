package com.samusynth.questcamera.io

/**
 * Optimized QOI (Quite OK Image) encoder implementation
 * Based on QOI specification: https://qoiformat.org/qoi-specification.pdf
 * 
 * QOI is a lossless image compression format designed for simplicity and speed.
 * 
 * Performance optimizations:
 * - Zero-allocation encoding using packed integers instead of objects
 * - Direct ByteArray manipulation instead of ByteArrayOutputStream
 * - Inline functions for hot paths
 * - IntArray for index buffer (no object wrappers)
 */
object QoiEncoder {
    
    private const val QOI_OP_INDEX = 0x00 // 00xxxxxx
    private const val QOI_OP_DIFF = 0x40  // 01xxxxxx
    private const val QOI_OP_LUMA = 0x80  // 10xxxxxx
    private const val QOI_OP_RUN = 0xc0   // 11xxxxxx
    private const val QOI_OP_RGB = 0xfe   // 11111110
    private const val QOI_OP_RGBA = 0xff  // 11111111
    
    private const val QOI_MAGIC = 0x716f6966 // "qoif" in ASCII
    private const val QOI_HEADER_SIZE = 14
    private const val QOI_END_MARKER_SIZE = 8
    
    /**
     * Encode RGB image data to QOI format
     * 
     * @param rgb RGB pixel data (packed as R,G,B,R,G,B,...)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return QOI-encoded byte array
     */
    fun encodeRgb(rgb: ByteArray, width: Int, height: Int): ByteArray {
        // Pre-allocate maximum possible size
        // Worst case: every pixel is QOI_OP_RGB (4 bytes each) + header + end marker
        val maxSize = QOI_HEADER_SIZE + (width * height * 4) + QOI_END_MARKER_SIZE
        val output = ByteArray(maxSize)
        var writePos = 0
        
        // Write QOI header
        // colorspace = 0 for sRGB (Android camera data is gamma-corrected sRGB)
        writePos = writeHeader(output, writePos, width, height, channels = 3, colorspace = 0)
        
        // Encode pixels
        writePos = encodePixels(rgb, width, height, channels = 3, output, writePos)
        
        // Write end marker
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 1
        
        // Return exactly sized array
        return output.copyOf(writePos)
    }
    
    /**
     * Encode RGBA image data to QOI format
     */
    fun encodeRgba(rgba: ByteArray, width: Int, height: Int): ByteArray {
        // Worst case: every pixel is QOI_OP_RGBA (5 bytes each) + header + end marker
        val maxSize = QOI_HEADER_SIZE + (width * height * 5) + QOI_END_MARKER_SIZE
        val output = ByteArray(maxSize)
        var writePos = 0
        
        // colorspace = 0 for sRGB with linear alpha
        writePos = writeHeader(output, writePos, width, height, channels = 4, colorspace = 0)
        writePos = encodePixels(rgba, width, height, channels = 4, output, writePos)
        
        // Write end marker
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 0
        output[writePos++] = 1
        
        return output.copyOf(writePos)
    }
    
    private fun writeHeader(
        output: ByteArray,
        writePos: Int,
        width: Int,
        height: Int,
        channels: Int,
        colorspace: Int
    ): Int {
        var pos = writePos
        
        // Magic bytes "qoif"
        output[pos++] = ((QOI_MAGIC ushr 24) and 0xff).toByte()
        output[pos++] = ((QOI_MAGIC ushr 16) and 0xff).toByte()
        output[pos++] = ((QOI_MAGIC ushr 8) and 0xff).toByte()
        output[pos++] = (QOI_MAGIC and 0xff).toByte()
        
        // Width (32-bit big-endian)
        output[pos++] = ((width ushr 24) and 0xff).toByte()
        output[pos++] = ((width ushr 16) and 0xff).toByte()
        output[pos++] = ((width ushr 8) and 0xff).toByte()
        output[pos++] = (width and 0xff).toByte()
        
        // Height (32-bit big-endian)
        output[pos++] = ((height ushr 24) and 0xff).toByte()
        output[pos++] = ((height ushr 16) and 0xff).toByte()
        output[pos++] = ((height ushr 8) and 0xff).toByte()
        output[pos++] = (height and 0xff).toByte()
        
        // Channels (1 byte)
        output[pos++] = channels.toByte()
        
        // Colorspace (1 byte)
        // 0 = sRGB with linear alpha (standard for camera data)
        // 1 = all channels linear (for HDR/scientific imaging)
        output[pos++] = colorspace.toByte()
        
        return pos
    }
    
    private fun encodePixels(
        pixels: ByteArray,
        width: Int,
        height: Int,
        channels: Int,
        output: ByteArray,
        startPos: Int
    ): Int {
        // Use IntArray to store packed RGBA values (eliminates object allocation)
        val index = IntArray(64) { packRGBA(0, 0, 0, 255) }
        var prevPixel = packRGBA(0, 0, 0, 255)
        var run = 0
        
        val pixelCount = width * height
        var pixelPos = 0
        var writePos = startPos
        
        for (i in 0 until pixelCount) {
            val r = pixels[pixelPos++].toInt() and 0xff
            val g = pixels[pixelPos++].toInt() and 0xff
            val b = pixels[pixelPos++].toInt() and 0xff
            val a = if (channels == 4) pixels[pixelPos++].toInt() and 0xff else 255
            
            val pixel = packRGBA(r, g, b, a)
            
            if (pixel == prevPixel) {
                run++
                if (run == 62 || i == pixelCount - 1) {
                    output[writePos++] = (QOI_OP_RUN or (run - 1)).toByte()
                    run = 0
                }
            } else {
                if (run > 0) {
                    output[writePos++] = (QOI_OP_RUN or (run - 1)).toByte()
                    run = 0
                }
                
                val indexPos = hashIndex(pixel)
                
                if (index[indexPos] == pixel) {
                    // QOI_OP_INDEX
                    output[writePos++] = (QOI_OP_INDEX or indexPos).toByte()
                } else {
                    index[indexPos] = pixel
                    
                    val prevA = prevPixel and 0xff
                    val pixelA = pixel and 0xff
                    
                    if (pixelA == prevA) {
                        val prevR = (prevPixel ushr 24) and 0xff
                        val prevG = (prevPixel ushr 16) and 0xff
                        val prevB = (prevPixel ushr 8) and 0xff
                        
                        val vr = r - prevR
                        val vg = g - prevG
                        val vb = b - prevB
                        
                        val vg_r = vr - vg
                        val vg_b = vb - vg
                        
                        if (vr in -2..1 && vg in -2..1 && vb in -2..1) {
                            // QOI_OP_DIFF
                            output[writePos++] = (QOI_OP_DIFF or ((vr + 2) shl 4) or ((vg + 2) shl 2) or (vb + 2)).toByte()
                        } else if (vg_r in -8..7 && vg in -32..31 && vg_b in -8..7) {
                            // QOI_OP_LUMA
                            output[writePos++] = (QOI_OP_LUMA or (vg + 32)).toByte()
                            output[writePos++] = (((vg_r + 8) shl 4) or (vg_b + 8)).toByte()
                        } else {
                            // QOI_OP_RGB
                            output[writePos++] = QOI_OP_RGB.toByte()
                            output[writePos++] = r.toByte()
                            output[writePos++] = g.toByte()
                            output[writePos++] = b.toByte()
                        }
                    } else {
                        // QOI_OP_RGBA
                        output[writePos++] = QOI_OP_RGBA.toByte()
                        output[writePos++] = r.toByte()
                        output[writePos++] = g.toByte()
                        output[writePos++] = b.toByte()
                        output[writePos++] = a.toByte()
                    }
                }
                
                prevPixel = pixel
            }
        }
        
        return writePos
    }
    
    /**
     * Pack RGBA components into a single Int for zero-allocation comparison
     * Format: 0xRRGGBBAA
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun packRGBA(r: Int, g: Int, b: Int, a: Int): Int {
        return (r shl 24) or (g shl 16) or (b shl 8) or a
    }
    
    /**
     * Calculate QOI hash index for packed RGBA value
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun hashIndex(rgba: Int): Int {
        val r = (rgba ushr 24) and 0xff
        val g = (rgba ushr 16) and 0xff
        val b = (rgba ushr 8) and 0xff
        val a = rgba and 0xff
        return (r * 3 + g * 5 + b * 7 + a * 11) and 63
    }
}

