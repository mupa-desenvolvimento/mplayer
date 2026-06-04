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

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yPlane.buffer.get(nv21, 0, ySize)

        val chromaRowStride = uPlane.rowStride
        val chromaPixelStride = uPlane.pixelStride

        val width = image.width
        val height = image.height
        var offset = ySize

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val uRow = ByteArray(chromaRowStride)
        val vRow = ByteArray(chromaRowStride)

        var row = 0
        while (row < height / 2) {
            uBuffer.get(uRow, 0, chromaRowStride)
            vBuffer.get(vRow, 0, chromaRowStride)

            var col = 0
            while (col < width / 2) {
                val uvOffset = col * chromaPixelStride
                nv21[offset++] = vRow[uvOffset]
                nv21[offset++] = uRow[uvOffset]
                col++
            }
            row++
        }

        return nv21
    }
}

