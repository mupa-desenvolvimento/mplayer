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
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Rewind to ensure we read from the beginning
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride = uPlane.rowStride
        val chromaPixelStride = uPlane.pixelStride
        val width = image.width
        val height = image.height

        var offset = ySize
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

