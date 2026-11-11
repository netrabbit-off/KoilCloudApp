package com.thekoil.koilcloud

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.thekoil.koilcloud.api.ApiClient

class ImagePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_MEDIA_IDS = "media_ids"
        const val EXTRA_MEDIA_NAMES = "media_names"
        const val EXTRA_MEDIA_INDEX = "media_index"
    }

    private var mediaIds: List<String> = emptyList()
    private var mediaNames: List<String> = emptyList()
    private var currentIndex: Int = 0

    private lateinit var imageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ApiClient.init(applicationContext)

        setContentView(R.layout.activity_image_preview)

        imageView = findViewById(R.id.previewImage)
        progress = findViewById(R.id.previewLoading)
        title = findViewById(R.id.previewTitle)

        mediaIds = intent.getStringArrayExtra(EXTRA_MEDIA_IDS)?.toList().orEmpty()
        mediaNames = intent.getStringArrayExtra(EXTRA_MEDIA_NAMES)?.toList().orEmpty()
        currentIndex = intent.getIntExtra(EXTRA_MEDIA_INDEX, 0)

        if (mediaIds.isEmpty()) {
            val id = intent.getStringExtra(EXTRA_FILE_ID)
            val name = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
            if (id != null) {
                mediaIds = listOf(id)
                mediaNames = listOf(name)
                currentIndex = 0
            } else {
                finish(); return
            }
        }

        findViewById<View>(R.id.previewClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { goTo(currentIndex - 1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { goTo(currentIndex + 1) }

        showCurrent()
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= mediaIds.size) return
        currentIndex = index
        showCurrent()
    }

    private fun showCurrent() {
        val id = mediaIds.getOrNull(currentIndex) ?: return
        val name = mediaNames.getOrNull(currentIndex).orEmpty()
        title.text = name

        val imageUrl = ApiClient.downloadUrl(id)
        val token = ApiClient.accessToken()
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

        progress.visibility = View.VISIBLE

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