package com.mupa.player.enterprise.player

import android.content.Context
import android.view.View
import android.widget.ImageView
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Job

internal class ImageEngine(context: Context) {
    private val imageLoader = ImageLoader(context)

    fun showFallback(imageView: ImageView) {
        if (imageView.drawable == null) {
            imageView.setImageResource(com.mupa.player.enterprise.R.drawable.ic_mplayer)
        }
    }

    fun clear(imageView: ImageView) {
        imageView.setImageDrawable(null)
    }

    fun loadInto(target: ImageView, file: Any): Job {
        target.visibility = View.VISIBLE
        val req =
            ImageRequest.Builder(target.context)
                .data(file)
                .target(target)
                .build()
        return imageLoader.enqueue(req).job
    }
}
