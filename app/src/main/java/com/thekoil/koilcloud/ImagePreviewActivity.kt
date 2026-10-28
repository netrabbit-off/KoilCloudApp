package com.thekoil.koilcloud

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target // <-- Важно: именно этот Target
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.thekoil.koilcloud.api.ApiClient

class ImagePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: run {
            finish(); return
        }
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""

        val imageView = findViewById<ImageView>(R.id.previewImage)
        val progress = findViewById<ProgressBar>(R.id.previewLoading)
        val title = findViewById<TextView>(R.id.previewTitle)
        val close = findViewById<View>(R.id.previewClose)

        title.text = fileName
        close.setOnClickListener { finish() }

        val imageUrl = ApiClient.downloadUrl(fileId)
        val token = ApiClient.token

        val loadModel: Any = if (token.isNullOrBlank()) {
            imageUrl
        } else {
            GlideUrl(
                imageUrl,
                LazyHeaders.Builder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            )
        }

        Glide.with(this)
            .load(loadModel)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progress.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progress.visibility = View.GONE
                    return false
                }
            })
            .into(imageView)
    }
}