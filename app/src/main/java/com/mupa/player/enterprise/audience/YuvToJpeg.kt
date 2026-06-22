package com.mupa.player.enterprise.audience

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object YuvToJpeg {
    fun imageProxyToJpegBytes(image: ImageProxy, jpegQuality: Int): ByteArray {
        val nv21 = imageProxyToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
        return out.toByteArray()
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Rewind buffers
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // Copy Y plane row by row, discarding padding
        var idY = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            val rowBuffer = ByteArray(yRowStride)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                val toRead = Math.min(yRowStride, yBuffer.remaining())
                yBuffer.get(rowBuffer, 0, toRead)
                for (col in 0 until width) {
                    nv21[idY++] = rowBuffer[col * yPixelStride]
                }
            }
        }

        // Copy chroma planes U/V
        val chromaRowStride = uPlane.rowStride
        val chromaPixelStride = uPlane.pixelStride
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        var offset = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * chromaRowStride + col * chromaPixelStride
                val vIndex = row * chromaRowStride + col * chromaPixelStride

                if (vIndex < vSize) {
                    nv21[offset] = vBuffer.get(vIndex)
                }
                if (uIndex < uSize) {
                    nv21[offset + 1] = uBuffer.get(uIndex)
                }
                offset += 2
            }
        }

        return nv21
    }
}
