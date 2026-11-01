package com.thekoil.koilcloud

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.thekoil.koilcloud.api.ApiClient

class MediaPlayerActivity : AppCompatActivity() {

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

    private var player: ExoPlayer? = null
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_player)

        title = findViewById(R.id.playerTitle)

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

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(ApiClient.authHeaders())

        val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        findViewById<PlayerView>(R.id.playerView).player = player

        findViewById<android.view.View>(R.id.btnPrev).setOnClickListener { goTo(currentIndex - 1) }
        findViewById<android.view.View>(R.id.btnNext).setOnClickListener { goTo(currentIndex + 1) }

        playCurrent()
    }

    private fun goTo(index: Int) {
        if (index < 0 || index >= mediaIds.size) return
        currentIndex = index
        playCurrent()
    }

    private fun playCurrent() {
        val id = mediaIds.getOrNull(currentIndex) ?: return
        val name = mediaNames.getOrNull(currentIndex).orEmpty()
        title.text = name

        val url = ApiClient.downloadUrl(id)
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}