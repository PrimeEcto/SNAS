package com.snas.app.playback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.snas.app.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ExoPlayerActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var overlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressTimeLeft: TextView
    private lateinit var progressTimeRight: TextView
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var progressScrubber: View
    private lateinit var playPauseBtn: TextView
    private lateinit var skipBackBtn: TextView
    private lateinit var skipForwardBtn: TextView
    private lateinit var ccBtn: TextView
    private lateinit var exitBtn: TextView
    private lateinit var loadingDot: TextView
    private lateinit var skipFeedback: TextView

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private var streamUrls = listOf<String>()
    private var streamIndex = 0
    private var fallbackIntent: Intent? = null
    private var currentTitle = ""
    private var seasonNum: Int? = null
    private var episodeNum: Int? = null
    private var subtitlesOn = true
    private var hasSubtitles = false
    private var hideRunnable: Runnable? = null
    private var positionPoller: kotlinx.coroutines.Job? = null

    companion object {
        private const val EXTRA_IMDB_ID = "imdbId"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_EMBED_URL = "embedUrl"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val SKIP_MS = 10_000L
        private const val OVERLAY_TIMEOUT = 5_000L
        private const val ACCENT = 0xFFE50914.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DIM = 0xFFB3B3B3.toInt()
        private const val MUTED = 0xFF808080.toInt()
        private const val SURFACE = 0xFF1A1A1A.toInt()
        private const val PLAYER_ORIGIN = "https://brightpathsignals.com"
        private const val PLAYER_REFERER = "https://brightpathsignals.com/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"

        fun createIntent(ctx: Context, item: com.snas.app.model.CatalogItem, season: Int? = null, episode: Int? = null) =
            Intent(ctx, ExoPlayerActivity::class.java)
                .putExtra(EXTRA_IMDB_ID, item.imdbId).putExtra(EXTRA_TYPE, item.type.name)
                .putExtra(EXTRA_TITLE, item.title).putExtra(EXTRA_EMBED_URL, item.embedUrl)
                .apply { if (season != null && episode != null) { putExtra(EXTRA_SEASON, season); putExtra(EXTRA_EPISODE, episode) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val imdbId = requireNotNull(intent.getStringExtra(EXTRA_IMDB_ID))
        val type = MediaType.valueOf(requireNotNull(intent.getStringExtra(EXTRA_TYPE)))
        val embedUrl = requireNotNull(intent.getStringExtra(EXTRA_EMBED_URL))
        seasonNum = intent.takeIf { it.hasExtra(EXTRA_SEASON) }?.getIntExtra(EXTRA_SEASON, 1)
        episodeNum = intent.takeIf { it.hasExtra(EXTRA_EPISODE) }?.getIntExtra(EXTRA_EPISODE, 1)
        fallbackIntent = WebViewPlayerActivity.createIntent(this, embedUrl, currentTitle)

        // ── Build view hierarchy ──────────────────────────────────
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        // PlayerView
        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Netflix subtitle style
            subtitleView?.setStyle(CaptionStyleCompat(WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        root.addView(playerView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // Overlay
        overlay = FrameLayout(this).apply {
            visibility = View.VISIBLE
            setBackgroundColor(Color.argb(60, 0, 0, 0))  // Lighter dim — video shows through
            isFocusable = false  // Don't steal focus — let child buttons have it
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        buildOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Loading dot
        loadingDot = textView("\u25CF", 32f, ACCENT, Gravity.CENTER).apply { visibility = View.VISIBLE }
        root.addView(loadingDot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Skip feedback
        skipFeedback = textView("", 28f, WHITE, Gravity.CENTER).apply {
            setShadowLayer(12f, 0f, 0f, Color.argb(200, 0, 0, 0)); visibility = View.GONE
        }
        root.addView(skipFeedback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // ── Track selector ────────────────────────────────────────
        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters().setForceHighestSupportedBitrate(true)
                .setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0).build()
        }

        // ── Player ────────────────────────────────────────────────
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build().also { p ->
            p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> { loadingDot.visibility = View.GONE; scheduleHide() }
                        Player.STATE_BUFFERING -> loadingDot.visibility = View.VISIBLE
                        Player.STATE_ENDED -> finish()
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    updatePlayPauseButton()
                    if (playing) scheduleHide()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    hasSubtitles = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                    updateCCButton()
                }
                override fun onPlayerError(error: PlaybackException) { playNextOrFallback() }
            })
        }
        playerView.player = player

        // Position polling — updates visual progress bar
        positionPoller = scope.launch {
            while (true) {
                val pos = player.currentPosition.coerceAtLeast(0); val dur = player.duration.takeIf { it > 0 } ?: 1L
                val pct = (pos.toFloat() / dur).coerceIn(0f, 1f)
                progressTimeLeft.text = formatTime(pos)
                progressTimeRight.text = formatTime(dur)
                // Update progress bar width
                val trackWidth = progressTrack.width
                if (trackWidth > 0) {
                    val fillWidth = (trackWidth * pct).toInt()
                    progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply { width = fillWidth }
                    progressFill.requestLayout()
                }
                delay(500)
            }
        }

        // Load stream
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    parseStreamApiResult(fetchUrl(buildStreamApiUrl(imdbId, type, seasonNum, episodeNum)))
                }
            }.getOrNull()
            streamUrls = result?.streamUrls.orEmpty()
            if (streamUrls.isEmpty()) { openWebFallback(); return@launch }
            if (DebridResolver.isAvailable) {
                streamUrls = withContext(Dispatchers.IO) { DebridResolver.resolveAll(streamUrls) }
            }
            currentTitle = result?.title ?: currentTitle
            titleText.text = currentTitle
            seasonNum?.let { s -> episodeNum?.let { e -> subtitleText.text = "S$s:E$e" } }
            playStream(0)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Overlay construction (all Android Views, no Compose)
    //  Netflix-style: lighter dim, cinematic gradients, split controls
    // ═══════════════════════════════════════════════════════════════

    private fun buildOverlay() {
        // Top bar — lighter Netflix-style gradient
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(32), dp(28), dp(56))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(180, 0, 0, 0), Color.argb(80, 0, 0, 0), Color.TRANSPARENT))
        }
        // Netflix N badge + season/episode
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nBadge = textView("N", 13f, WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(3).toFloat() }
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        badgeRow.addView(nBadge)
        subtitleText = textView("", 13f, DIM).apply { setPadding(dp(10), 0, 0, 0); typeface = Typeface.DEFAULT_BOLD }
        badgeRow.addView(subtitleText)
        topBar.addView(badgeRow)
        // Title below badge
        titleText = textView(currentTitle, 24f, WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(12f, 0f, 1f, Color.argb(200, 0, 0, 0))
            setPadding(0, dp(6), 0, 0)
        }
        topBar.addView(titleText)
        overlay.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Progress bar — thin Netflix-style with scrubber dot
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), 0, dp(32), 0)
        }
        // Time labels row
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        progressTimeLeft = textView("0:00:00", 11f, DIM)
        progressTimeRight = textView("0:00:00", 11f, DIM).apply { gravity = Gravity.END }
        timeRow.addView(progressTimeLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(progressTimeRight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        progressContainer.addView(timeRow)
        // Track + fill bar
        val trackFrame = FrameLayout(this).apply {
            setPadding(0, dp(6), 0, 0); minimumHeight = dp(20)
        }
        progressTrack = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = dp(2).toFloat() }
        }
        trackFrame.addView(progressTrack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.CENTER_VERTICAL))
        progressFill = View(this).apply {
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(2).toFloat() }
        }
        trackFrame.addView(progressFill, FrameLayout.LayoutParams(0, dp(3), Gravity.CENTER_VERTICAL))
        progressScrubber = View(this).apply {
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(8).toFloat() }
            visibility = View.GONE
        }
        trackFrame.addView(progressScrubber, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL))
        progressContainer.addView(trackFrame)
        overlay.addView(progressContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(80) })

        // Bottom bar — lighter gradient, split controls
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(8), dp(28), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Color.argb(200, 0, 0, 0)))
        }
        // Controls row — left side (play/skip), right side (CC/exit)
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val leftControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        skipBackBtn = playerButton("\u25C0\u25C0", false)
        playPauseBtn = playerButton("\u25B6", true)
        skipForwardBtn = playerButton("\u25B6\u25B6", false)
        leftControls.addView(skipBackBtn); leftControls.addView(playPauseBtn); leftControls.addView(skipForwardBtn)
        controlRow.addView(leftControls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rightControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        ccBtn = playerButton("CC \u2298", false)
        exitBtn = playerButton("\u2715", false)
        rightControls.addView(ccBtn); rightControls.addView(exitBtn)
        controlRow.addView(rightControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBar.addView(controlRow)
        overlay.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Focus chain: skipBack → playPause → skipForward → cc → exit
        skipBackBtn.nextFocusRightId = playPauseBtn.id
        playPauseBtn.nextFocusLeftId = skipBackBtn.id
        playPauseBtn.nextFocusRightId = skipForwardBtn.id
        skipForwardBtn.nextFocusLeftId = playPauseBtn.id
        skipForwardBtn.nextFocusRightId = ccBtn.id
        ccBtn.nextFocusLeftId = skipForwardBtn.id
        ccBtn.nextFocusRightId = exitBtn.id
        exitBtn.nextFocusLeftId = ccBtn.id

        // Wire button actions
        skipBackBtn.setOnClickListener { player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0)); showSkipFeedback("\u25C0 10s"); scheduleHide() }
        playPauseBtn.setOnClickListener { togglePlayPause(); scheduleHide() }
        skipForwardBtn.setOnClickListener { player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(player.duration)); showSkipFeedback("10s \u25B6"); scheduleHide() }
        ccBtn.setOnClickListener {
            if (hasSubtitles) { toggleSubtitles(); scheduleHide() }
        }
        exitBtn.setOnClickListener { finish() }
    }

    private fun textView(text: String, size: Float, color: Int, gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL): TextView {
        return TextView(this).apply { this.text = text; textSize = size; setTextColor(color); this.gravity = gravity; isFocusable = false }
    }

    private fun playerButton(label: String, isPrimary: Boolean = false): TextView {
        val id = View.generateViewId()
        return TextView(this).apply {
            this.id = id
            text = label; gravity = Gravity.CENTER
            setTextColor(WHITE); typeface = Typeface.DEFAULT_BOLD
            textSize = if (isPrimary) 18f else 14f
            val hPad = if (isPrimary) dp(32) else dp(18)
            val vPad = if (isPrimary) dp(14) else dp(10)
            setPadding(hPad, vPad, hPad, vPad)
            isFocusable = true; isClickable = true
            isFocusableInTouchMode = true
            minWidth = if (isPrimary) dp(120) else dp(70)
            minHeight = if (isPrimary) dp(52) else dp(44)
            background = buttonBg(isPrimary, false)
            setOnFocusChangeListener { _, hasFocus ->
                scaleOnFocus(this, hasFocus)
                background = buttonBg(isPrimary, hasFocus)
            }
        }
    }

    private fun buttonBg(primary: Boolean, focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()  // Pill shape
            when {
                focused && primary -> { setColor(ACCENT); setStroke(dp(3), WHITE) }
                focused -> { setColor(Color.argb(200, 70, 70, 70)); setStroke(dp(2), WHITE) }
                primary -> setColor(Color.argb(230, 229, 9, 20))
                else -> setColor(Color.argb(160, 60, 60, 60))
            }
        }
    }

    private fun scaleOnFocus(view: View, focused: Boolean) {
        view.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f)
            .setDuration(150).start()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Playback controls
    // ═══════════════════════════════════════════════════════════════

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        playPauseBtn.text = if (player.isPlaying) "\u23F8" else "\u25B6"
    }

    private fun toggleSubtitles() {
        subtitlesOn = !subtitlesOn
        val p = trackSelector.buildUponParameters()
        if (subtitlesOn) {
            // Enable any available text track — don't filter by language
            p.setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(true).setIgnoredTextSelectionFlags(0)
        } else {
            p.setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(false)
        }
        trackSelector.parameters = p.build()
        updateCCButton()
    }

    private fun updateCCButton() {
        when {
            !hasSubtitles -> { ccBtn.text = "CC \u2298"; ccBtn.alpha = 0.4f }
            subtitlesOn -> { ccBtn.text = "CC ON"; ccBtn.alpha = 1f }
            else -> { ccBtn.text = "CC OFF"; ccBtn.alpha = 1f }
        }
    }

    private fun showSkipFeedback(label: String) {
        skipFeedback.text = label; skipFeedback.visibility = View.VISIBLE
        scope.launch { delay(800); skipFeedback.visibility = View.GONE }
    }

    private fun moveFocusLeft() {
        val focused = overlay.findFocus()
        when (focused?.id) {
            playPauseBtn.id -> skipBackBtn.requestFocus()
            skipForwardBtn.id -> playPauseBtn.requestFocus()
            ccBtn.id -> skipForwardBtn.requestFocus()
            exitBtn.id -> ccBtn.requestFocus()
            else -> playPauseBtn.requestFocus()
        }
    }

    private fun moveFocusRight() {
        val focused = overlay.findFocus()
        when (focused?.id) {
            skipBackBtn.id -> playPauseBtn.requestFocus()
            playPauseBtn.id -> skipForwardBtn.requestFocus()
            skipForwardBtn.id -> ccBtn.requestFocus()
            ccBtn.id -> exitBtn.requestFocus()
            else -> playPauseBtn.requestFocus()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Universal TV remote handling
    //  Fire TV / Google TV / Android TV / NVIDIA Shield / Xiaomi TV
    // ═══════════════════════════════════════════════════════════════

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val overlayVisible = overlay.visibility == View.VISIBLE

        return when (keyCode) {
            // ── Play / Pause (all variants across all remotes) ──
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_SPACE -> {
                // KEYCODE_SPACE = some Google TV / Chromecast remotes
                player.play()
                if (!overlayVisible) showOverlay(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                // KEYCODE_MEDIA_STOP = some generic Android TV boxes
                player.pause()
                if (!overlayVisible) showOverlay(); true
            }

            // ── Skip forward (all variants) ──
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                // KEYCODE_MEDIA_NEXT = Shield / Xiaomi / generic Android TV
                player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(player.duration))
                showSkipFeedback("10s \u25B6"); true
            }

            // ── Skip backward (all variants) ──
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                // KEYCODE_MEDIA_PREVIOUS = Shield / Xiaomi / generic Android TV
                player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0))
                showSkipFeedback("\u25C0 10s"); true
            }

            // ── Back (all variants) ──
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                // KEYCODE_ESCAPE = some generic Android boxes
                if (overlayVisible) { hideOverlay(); true }
                else { finish(); true }
            }

            // ── Select / OK (all variants across all remotes) ──
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                // KEYCODE_BUTTON_A = Shield gamepad / some generic
                // KEYCODE_BUTTON_SELECT = some Xiaomi / generic Android TV
                if (!overlayVisible) { showOverlay(); true }
                else { /* button click listeners handle it */ false }
            }

            // ── Dpad navigation ──
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!overlayVisible) {
                    player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0))
                    showSkipFeedback("\u25C0 10s")
                    showOverlay()
                } else {
                    scheduleHide()
                    moveFocusLeft()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!overlayVisible) {
                    player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(player.duration))
                    showSkipFeedback("10s \u25B6")
                    showOverlay()
                } else {
                    scheduleHide()
                    moveFocusRight()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { showOverlay(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); true }

            // ── Menu / Info = toggle overlay (Shield / Google TV / Xiaomi) ──
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                if (overlayVisible) hideOverlay() else showOverlay(); true
            }

            // ── Home / Record = ignore (don't accidentally trigger) ──
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MEDIA_RECORD -> true

            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Overlay show/hide
    // ═══════════════════════════════════════════════════════════════

    private fun showOverlay() {
        cancelHide()
        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f
        // Focus play/pause immediately (post ensures view is attached)
        playPauseBtn.post { playPauseBtn.requestFocus() }
        scheduleHide()
    }

    private fun hideOverlay() {
        cancelHide()
        overlay.animate().alpha(0f).setDuration(300).withEndAction { overlay.visibility = View.GONE }.start()
        overlay.alpha = 1f // Reset for next show
    }

    private fun scheduleHide() {
        cancelHide()
        hideRunnable = Runnable { hideOverlay() }
        handler.postDelayed(hideRunnable!!, OVERLAY_TIMEOUT)
    }

    private fun cancelHide() { hideRunnable?.let { handler.removeCallbacks(it) } }

    // ═══════════════════════════════════════════════════════════════
    //  Stream / Lifecycle
    // ═══════════════════════════════════════════════════════════════

    private fun playStream(index: Int) {
        streamIndex = index
        val src = HlsMediaSource.Factory(httpFactory()).createMediaSource(MediaItem.fromUri(streamUrls[index]))
        player.setMediaSource(src); player.prepare(); player.playWhenReady = true
    }

    private fun playNextOrFallback() {
        if (streamIndex + 1 < streamUrls.size) { streamIndex++; playStream(streamIndex) }
        else openWebFallback()
    }

    private fun openWebFallback() { fallbackIntent?.let { startActivity(it) }; finish() }

    private fun httpFactory() = DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
        .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Referer" to PLAYER_REFERER, "Origin" to PLAYER_ORIGIN))

    private fun fetchUrl(url: String): String {
        val c = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", PLAYER_REFERER); setRequestProperty("Origin", PLAYER_ORIGIN)
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    override fun onStop() { player.pause(); super.onStop() }
    override fun onDestroy() {
        cancelHide(); positionPoller?.cancel()
        playerView.player = null; player.release(); scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).toInt()
        return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
