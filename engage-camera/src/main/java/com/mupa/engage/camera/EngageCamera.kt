package com.mupa.engage.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis

object EngageCamera {
    fun frontSelector(): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
    }

    fun analysisUseCase(): ImageAnalysis {
        val b =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        runCatching { b.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) }
        return b.build()
    }
}
