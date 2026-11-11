package com.karaoketv

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The whole app is this one screen with layers:
 *   LOBBY        browse songs, see the queue, phones join by QR code, quiet lobby music
 *   INTERMISSION "Next up: Maria!" with a countdown while the video loads
 *   SINGING      full-screen video; ◀ ▶ music volume, ▼ queue panel (add songs without stopping)
 *   SCORE        drumroll → score → leaderboard rank → next singer
 * One Activity keeps memory low, and the YouTube player only exists while the party is running.
 */
class MainActivity : Activity(), PartyBackend.Listener {

    private enum class Mode { LOBBY, INTERMISSION, SINGING, SCORE }

    private var mode = Mode.LOBBY
    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newFixedThreadPool(2)

    private lateinit var prefs: SharedPreferences
    private lateinit var queue: PartyQueue
    private lateinit var search: SongSearch
    private lateinit var board: Leaderboard
    private lateinit var music: LobbyMusic
    private lateinit var sfx: Sfx

    // Layers
    private lateinit var stageBg: StageView
    private lateinit var stageContainer: FrameLayout
    private lateinit var lobby: View
    private lateinit var hud: View
    private lateinit var queuePanel: View
    private lateinit var intermission: View
    private lateinit var scoreLayer: View
    private lateinit var boardLayer: View
    private lateinit var pickerLayer: View
    private lateinit var sheetLayer: View
    private lateinit var qrLayer: View
    private lateinit var countryLayer: View
    private lateinit var volumeLayer: View
    private lateinit var stageTouch: View
    private lateinit var stageCurtain: View
    private lateinit var toastView: TextView
    private lateinit var confetti: ConfettiView
    private lateinit var lobbyBrowser: SongBrowser
    private lateinit var panelBrowser: SongBrowser

    // Player
    private var playerView: YouTubePlayerView? = null
    private var player: YouTubePlayer? = null
    private var playerReady = false
    private var startWhenReady = false
    private var pendingCue: String? = null
    private var duration = 0f
    private var maxSecond = 0f
    private var isPlaying = false
    /** The last seconds of the song are covered (owners put suggestion tiles there). */
    private var finale = false
    /** Really paused (not just loading or buffering). */
    private var isPaused = false
    /** Phone or tablet: show buttons instead of remote hints. */
    private var isTouch = false
    private var volume = 80

    // Phones
    private var server: PartyServer? = null
    private var partyUrl: String? = null

    // Misc state
    private var lastInteraction = System.currentTimeMillis()
    private var backPressedAt = 0L
    private var countdown = 0
    private var firstLobbyLoad = true
    private var scoreDone = false
    private var scoreAnim: ValueAnimator? = null
    private var autoNextIn = 0
    private var pickerSong: Song? = null
    private var overlayReturnFocus: View? = null
    private var sheetCancel: (() -> Unit)? = null
    private var songsOnThisPlayer = 0
    /** Key of the queue item whose refused video is being replaced (0 = none). */
    private var repairingKey = 0L

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /** Scale the whole interface to the TV (see TvScale). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // If scaling ever fails, the app still opens (at the device's own size)
        runCatching { applyOverrideConfiguration(TvScale.configuration(newBase)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            // Debug builds log any leaked screen, unclosed stream or receiver (LeakCanary also watches)
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks().detectLeakedClosableObjects().detectLeakedRegistrationObjects()
                    .penaltyLog().build()
            )
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        volume = prefs.getInt("music_volume", 80)
        queue = PartyQueue(ui).also { it.onChanged = ::onQueueChanged }
        search = SongSearch(this).also {
            it.country = runCatching { Countries.byCode(prefs.getString("country", "")) }.getOrDefault(Countries.INTERNATIONAL)
        }
        board = Leaderboard(this)
        music = LobbyMusic(this)
        sfx = Sfx(this).also { it.master = volume / 100f }

        stageBg = findViewById(R.id.stageBg)
        stageContainer = findViewById(R.id.stageContainer)
        lobby = findViewById(R.id.lobby)
        hud = findViewById(R.id.hud)
        queuePanel = findViewById(R.id.queuePanel)
        intermission = findViewById(R.id.intermission)
        scoreLayer = findViewById(R.id.scoreLayer)
        boardLayer = findViewById(R.id.boardLayer)
        pickerLayer = findViewById(R.id.pickerLayer)
        sheetLayer = findViewById(R.id.sheetLayer)
        qrLayer = findViewById(R.id.qrLayer)
        countryLayer = findViewById(R.id.countryLayer)
        volumeLayer = findViewById(R.id.volumeLayer)
        stageTouch = findViewById(R.id.stageTouch)
        stageCurtain = findViewById(R.id.stageCurtain)
        toastView = findViewById(R.id.toast)
        confetti = findViewById(R.id.confetti)

        lobbyBrowser = SongBrowser(
            this, search, io,
            findViewById(R.id.lobbySearch), findViewById(R.id.lobbyChips), findViewById(R.id.lobbyStatus),
            findViewById(R.id.lobbyScroll), findViewById(R.id.lobbyResults), columns = 3,
        ) { openPicker(it) }
        lobbyBrowser.onResults = { found ->
            if (firstLobbyLoad && found && mode == Mode.LOBBY && !anyOverlayOpen()) lobbyBrowser.focusFirst()
            firstLobbyLoad = false
        }

        panelBrowser = SongBrowser(
            this, search, io,
            findViewById(R.id.panelSearch), findViewById(R.id.panelChips), findViewById(R.id.panelStatus),
            findViewById(R.id.panelScroll), findViewById(R.id.panelResults), columns = 1,
        ) { openPicker(it) }

        click(R.id.btnBoard) { showBoard() }
        click(R.id.btnVoice) { voiceSearch() }
        click(R.id.btnCountry) { showCountries() }
        findViewById<EditText>(R.id.countryFilter).apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (countryLayer.isShown) renderCountries(s?.toString().orEmpty(), focus = false)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                hideKeyboard()
                firstCountryButton()?.requestFocus()
                true
            }
        }
        click(R.id.qrCard) { showBigQr() }
        click(R.id.bigQrClose) { hideBigQr() }
        click(R.id.btnMusic) { showVolumePanel() }
        click(R.id.volumeDone) { hideVolumePanel() }
        click(R.id.volumeMusic) {
            music.enabled = !music.enabled
            updateVolumePanel()
            updateMusicButton()
        }
        findViewById<SeekBar>(R.id.volumeSlider).apply {
            keyProgressIncrement = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) setVolumeTo(value, save = false)   // saved when the pop-up closes
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { setVolumeTo(bar.progress) }
            })
        }
        click(R.id.btnStart) { startNext() }
        click(R.id.btnPanelAdd) { panelSearchMode() }
        click(R.id.pickerGo) { confirmPicker(findViewById<EditText>(R.id.pickerInput).text.toString()) }
        click(R.id.pickerCancel) { closePicker() }
        for ((tab, view) in listOf(R.id.tabTonight to BoardView.TONIGHT, R.id.tabAll to BoardView.ALL, R.id.tabDuets to BoardView.DUETS)) {
            click(tab) { renderBoard(view) }
            findViewById<View>(tab).setOnFocusChangeListener { _, f -> if (f) renderBoard(view) }
        }
        findViewById<EditText>(R.id.pickerInput).setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { confirmPicker(v.text.toString()); true } else false
        }

        setUpTouch()
        updateMusicButton()
        applyCountry()
        updateQr()
        QueueStore.restore(prefs, queue)   // songs survive an accidental exit
        goLobby()
        lobbyBrowser.runQuickPick(0)
    }

    /**
     * Phones and tablets: hide the status and navigation bars so the whole screen is the party.
     * A swipe from the edge shows them briefly; they hide again by themselves. No effect on TVs.
     */
    private fun goFullScreen() = try {
        hideSystemBars()
    } catch (e: Exception) {
        // Cosmetic only: never let this stop the party
    }

    private fun hideSystemBars() {
        val w = window
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            w.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** The bars come back after dialogs, the keyboard or voice search: hide them again. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onStart() {
        super.onStart()
        startServer()
        if (mode == Mode.LOBBY || mode == Mode.INTERMISSION) music.start()
        stageBg.animating = mode != Mode.SINGING
        ui.postDelayed(autopilot, 5000)
        // resume whatever was counting down when we left
        if (mode == Mode.INTERMISSION) { ui.removeCallbacks(countdownTick); ui.post(countdownTick) }
        if (mode == Mode.SCORE && scoreDone) { ui.removeCallbacks(scoreAutoNext); ui.postDelayed(scoreAutoNext, 1000) }
        if (mode == Mode.SCORE) { ui.removeCallbacks(scoreMusic); ui.postDelayed(scoreMusic, 1500) }
        if (mode == Mode.SINGING && finale) { ui.removeCallbacks(finaleDone); ui.postDelayed(finaleDone, 1500) }
    }

    override fun onStop() {
        stopListening()
        QueueStore.save(prefs, queue)
        ui.removeCallbacks(autopilot)
        ui.removeCallbacks(countdownTick)
        ui.removeCallbacks(scoreAutoNext)
        ui.removeCallbacks(scoreMusic)
        ui.removeCallbacks(finaleDone)
        stopServer()
        music.stop(0)
        if (mode == Mode.SINGING) player?.pause()
        stageBg.animating = false
        super.onStop()
    }

    override fun onDestroy() {
        // Break every link from long-lived objects back to this screen
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        stopServer()
        ui.removeCallbacksAndMessages(null)
        queue.onChanged = null
        lobbyBrowser.onResults = null
        scoreAnim?.removeAllListeners()
        scoreAnim?.cancel()
        listOf(scoreLayer, lobby, toastView, intermission).forEach { it.animate().cancel() }
        confetti.clear()
        stageBg.animating = false
        releasePlayer()
        music.release()
        sfx.release()
        if (isFinishing) ImageLoader.clear() else ImageLoader.cancelAll()
        io.shutdownNow()
        search.close()
        super.onDestroy()
    }

    @Deprecated("Activity result API from the platform; fine for a single voice-search request")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty() && mode == Mode.LOBBY) lobbyBrowser.searchFor(text.take(80))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ImageLoader.clear()
        search.trimMemory()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (mode != Mode.LOBBY) lobbyBrowser.clear()
            if (!queuePanel.isShown) panelBrowser.clear()
            // a fresh player next time sheds whatever the web page has accumulated
            if (mode == Mode.SCORE) songsOnThisPlayer = RECYCLE_PLAYER_AFTER
        }
    }

    // =====================================================================================
    // Remote control
    // =====================================================================================

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastInteraction = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInteraction = System.currentTimeMillis()
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val code = e.keyCode
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

        // Overlays first, topmost first (a confirmation sheet can sit on top of the picker)
        if (sheetLayer.isShown) { if (back) { if (first) cancelSheet(); return true }; return super.dispatchKeyEvent(e) }
        if (pickerLayer.isShown) { if (back) { if (first) pickerBack(); return true }; return super.dispatchKeyEvent(e) }
        if (boardLayer.isShown) { if (back) { if (first) hideBoard(); return true }; return super.dispatchKeyEvent(e) }
        if (qrLayer.isShown) { if (back) { if (first) hideBigQr(); return true }; return super.dispatchKeyEvent(e) }
        if (countryLayer.isShown) { if (back) { if (first) hideCountries(); return true }; return super.dispatchKeyEvent(e) }
        if (volumeLayer.isShown) { if (back) { if (first) hideVolumePanel(); return true }; return super.dispatchKeyEvent(e) }

        when (mode) {
            Mode.LOBBY -> {
                if (back) { if (first) { if (recognizer != null) stopListening() else lobbyBack() }; return true }
                if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST) { if (first) voiceSearch(); return true }
                return super.dispatchKeyEvent(e)
            }

            Mode.INTERMISSION -> {
                when {
                    ok || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                        if (first) beginSinging()
                    code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (first) singLater()
                    back -> if (first) intermissionToLobby()
                }
                return true
            }

            Mode.SINGING -> {
                if (queuePanel.isShown) {
                    if (back) { if (first) panelBack(); return true }
                    return super.dispatchKeyEvent(e)
                }
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) changeVolume(-5)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) changeVolume(+5)
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> if (first) openPanel()
                    KeyEvent.KEYCODE_DPAD_UP -> if (first) showStageInfo()
                    KeyEvent.KEYCODE_MEDIA_PLAY -> if (first) { if (finale) showScore(1.0) else player?.play() }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first) player?.pause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> if (first) afterSong()
                    else -> when {
                        ok || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_SPACE ->
                            if (first) togglePause()
                        back || code == KeyEvent.KEYCODE_MEDIA_STOP -> if (first) stageMenu()
                        else -> return super.dispatchKeyEvent(e)
                    }
                }
                return true
            }

            Mode.SCORE -> {
                if ((ok || back) && first && scoreDone) afterSong()
                return true
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    private fun lobbyBack() {
        val now = System.currentTimeMillis()
        if (now - backPressedAt < 2500) { finish(); return }
        backPressedAt = now
        toast("Press BACK again to leave the party")
    }

    // =====================================================================================
    // Lobby
    // =====================================================================================

    private fun goLobby() {
        mode = Mode.LOBBY
        repairingKey = 0L
        ui.removeCallbacks(countdownTick)
        ui.removeCallbacks(scoreAutoNext)
        scoreAnim?.cancel()
        queue.current = null
        releasePlayer()
        stage(false)
        listOf(hud, queuePanel, intermission, scoreLayer).forEach { it.visibility = View.GONE }
        lobby.visibility = View.VISIBLE
        lobby.alpha = 0f
        lobby.animate().alpha(1f).setDuration(350).start()
        stageBg.animating = true
        music.start()
        renderQueues()
        if (!lobbyBrowser.hasResults()) lobbyBrowser.lastQuery?.let { lobbyBrowser.run(it) }
        val start = findViewById<View>(R.id.btnStart)
        if (start.isShown) start.requestFocus() else if (!lobbyBrowser.focusFirst()) findViewById<View>(R.id.lobbySearch).requestFocus()
    }

    private fun updateMusicButton() {
        findViewById<TextView>(R.id.btnMusic).text = if (volume == 0) "🔇" else if (music.enabled) "🎵" else "🔈"
    }

    /** Starts the party by itself if songs are waiting and nobody has touched the remote for a while. */
    private val autopilot = object : Runnable {
        override fun run() {
            if (mode == Mode.LOBBY && !queue.isEmpty() && !anyOverlayOpen() &&
                System.currentTimeMillis() - lastInteraction > AUTOSTART_MS
            ) {
                toast("🎤 Songs are waiting. Let's go!")
                startNext()
            }
            ui.postDelayed(this, 5000)
        }
    }

    // =====================================================================================
    // Queue display
    // =====================================================================================

    private fun onQueueChanged() {
        QueueStore.save(prefs, queue)
        renderQueues()
        if (mode == Mode.SCORE && scoreDone) updateScoreNext()
        if (mode == Mode.INTERMISSION) updateIntermissionInfo()
        if (mode == Mode.SINGING) updateNextPill()
    }

    private fun renderQueues() {
        val items = queue.snapshot()
        renderQueueList(findViewById(R.id.lobbyQueueList), items, 30, fallback = findViewById(R.id.btnStart), guide = true)
        findViewById<TextView>(R.id.lobbyQueueTitle).text = if (items.isEmpty()) "UP NEXT" else "UP NEXT · ${items.size}"
        val start = findViewById<TextView>(R.id.btnStart)
        val wasFocused = start.isFocused
        start.visibility = if (items.isNotEmpty() && mode == Mode.LOBBY) View.VISIBLE else View.GONE
        if (items.isNotEmpty()) start.text = "▶  START: ${items[0].singer.uppercase()}"
        if (wasFocused && !start.isShown) lobbyBrowser.focusFirst()

        if (queuePanel.isShown) renderQueueList(findViewById(R.id.panelQueueList), items, 30, fallback = findViewById(R.id.btnPanelAdd))
        findViewById<TextView>(R.id.panelTitle).text = if (items.isEmpty()) "UP NEXT" else "UP NEXT · ${items.size}"
    }

    private fun renderQueueList(list: LinearLayout, items: List<QueueItem>, max: Int, fallback: View, guide: Boolean = false) {
        val hadFocus = list.findFocus() != null
        list.removeAllViews()
        items.take(max).forEachIndexed { i, item ->
            val row = layoutInflater.inflate(R.layout.item_queue, list, false)
            row.findViewById<TextView>(R.id.qAvatar).avatar(item.singer)
            row.findViewById<TextView>(R.id.qSinger).text = "${i + 1}. ${item.singer}"
            row.findViewById<TextView>(R.id.qSong).text = item.song.title
            row.findViewById<TextView>(R.id.qBadge).text = if (item.fromPhone) "📱" else ""
            row.setOnClickListener { queueItemMenu(item) }
            list.addView(row)
        }
        if (items.size > max) list.addView(smallText("+ ${items.size - max} more waiting"))
        if (items.isEmpty()) list.addView(smallText(
            if (guide) "How it works:\n\n1.  Pick a song\n2.  Choose who sings\n3.  Press ▶ START\n\n📱 Guests can scan the code below to add songs from their phones."
            else "No songs yet. Add one below, or scan the code with your phone!"
        ).apply { textSize = 14f; setLineSpacing(0f, 1.15f) })
        if (hadFocus) {
            val target = list.getChildAt(0)?.takeIf { it.isFocusable } ?: fallback
            if (!target.requestFocus()) lobbyBrowser.focusFirst()
        }
    }

    private fun smallText(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(getColor(R.color.text_dim))
        setPadding(px(6), px(8), px(6), px(4))
    }

    private fun queueItemMenu(item: QueueItem) {
        showSheet(
            item.singer, item.song.title,
            listOf(
                "⬆ Sing next" to { queue.moveToTop(item.key) },
                "🗑 Remove" to { queue.remove(item.key) },
                "Cancel" to {},
            )
        )
    }

    // =====================================================================================
    // Singer picker ("Who's singing?")
    // =====================================================================================

    /** Duet mode in the picker: two people are chosen one after the other. */
    private var pickerDuet = false
    private var duetFirst: String? = null
    /** Remove mode: tapping a player asks to remove them (and their scores). */
    private var pickerRemove = false

    private fun openPicker(song: Song) {
        pickerSong = song
        pickerDuet = false
        duetFirst = null
        pickerRemove = false
        overlayReturnFocus = currentFocus
        findViewById<TextView>(R.id.pickerSong).text = song.title
        pickerLayer.visibility = View.VISIBLE
        popIn(pickerLayer)
        renderPicker()
    }

    /** Names, duet and surprise buttons, title and the Add button, for the current step. */
    private fun renderPicker() {
        val input = findViewById<EditText>(R.id.pickerInput)
        input.setText("")
        val first = duetFirst
        findViewById<TextView>(R.id.pickerTitle).text = when {
            pickerRemove -> "🗑 REMOVE A PLAYER"
            !pickerDuet -> "🎤 WHO'S SINGING?"
            first == null -> "👫 DUET: WHO'S FIRST?"
            else -> "👫 WHO SINGS WITH ${first.uppercase()}?"
        }
        findViewById<TextView>(R.id.pickerSong).text =
            if (pickerRemove) "Choose who to remove. Their scores leave the hall of fame." else pickerSong?.title.orEmpty()
        findViewById<View>(R.id.pickerInputRow).visibility = if (pickerRemove) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pickerCancel).visibility = if (pickerRemove) View.GONE else View.VISIBLE
        // Singers alone: queued solo singers, recent singers and both halves of recent duets.
        // Remove mode lists saved players only (12 fit, 4 per row, without the typing row).
        val names = (if (pickerRemove) board.players()
        else queue.snapshot().flatMap { it.singer.split(DUET_JOIN) } +
            (queue.current?.singer?.split(DUET_JOIN) ?: emptyList()) + board.recentNames(12))
            .distinctBy { it.lowercase() }
            .filter { !it.equals(first, ignoreCase = true) }
            .take(if (pickerRemove) 12 else 8)
        val box = findViewById<LinearLayout>(R.id.pickerNames)
        box.removeAllViews()
        names.chunked(if (pickerRemove) 4 else 3).forEach { rowNames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rowNames.forEach { name ->
                row.addView(styledButton(if (pickerRemove) "✕ $name" else name, false).apply {
                    maxWidth = px(if (pickerRemove) 135 else 170)   // long names get "…" instead of pushing the row off screen
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener { if (pickerRemove) askRemovePlayer(name) else confirmPicker(name) }
                },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52)).apply { setMargins(px(5), px(5), px(5), px(5)) })
            }
            box.addView(row)
        }
        // Own row: duet switch, a one-click fun stage name (no typing needed), remove players
        val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52)).apply { setMargins(px(5), px(5), px(5), px(5)) } }
        if (pickerRemove) {
            if (names.isEmpty()) box.addView(label("No saved players.", 16f, R.color.text_dim).apply { gravity = Gravity.CENTER })
            extras.addView(styledButton("✓ Done", true).apply { setOnClickListener { pickerRemove = false; renderPicker() } }, lp())
        } else {
            if (first == null) {
                extras.addView(styledButton(if (pickerDuet) "🎤 Solo instead" else "👫 Duet", false).apply {
                    setOnClickListener { pickerDuet = !pickerDuet; duetFirst = null; renderPicker() }
                }, lp())
            }
            extras.addView(styledButton("🎲 Surprise name", false).apply { setOnClickListener { confirmPicker(randomStageName()) } }, lp())
            if (first == null && board.players().isNotEmpty()) {
                extras.addView(styledButton("🗑", false).apply {
                    contentDescription = "Remove a player"
                    setOnClickListener { pickerRemove = true; pickerDuet = false; renderPicker() }
                }, lp())
            }
        }
        box.addView(extras)
        box.visibility = View.VISIBLE
        val singNow = mode == Mode.LOBBY && queue.isEmpty()
        findViewById<TextView>(R.id.pickerGo).text = when {
            pickerDuet && first == null -> "Next ›"
            singNow -> "🎤 Sing now!"
            else -> "＋ Add"
        }
        lockFocus()
        val firstName = (box.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        (firstName ?: input).requestFocus()
    }

    /** BACK in the picker steps back: out of remove mode, undo the first duet pick, then close. */
    private fun pickerBack() {
        when {
            pickerRemove -> { pickerRemove = false; renderPicker() }
            duetFirst != null -> { duetFirst = null; renderPicker() }
            else -> closePicker()
        }
    }

    /** Second confirmation before a player and their scores are removed. "Keep" is the default. */
    private fun askRemovePlayer(name: String) {
        val back = overlayReturnFocus            // where focus goes when the picker closes
        showSheet(
            "Remove $name?",
            "$name and all their scores (solo and duets) will be deleted from the hall of fame. This can't be undone.",
            listOf(
                "Keep $name" to { overlayReturnFocus = back; renderPicker() },
                "🗑 Remove" to {
                    overlayReturnFocus = back
                    if (board.removePlayer(name)) toast("🗑 $name removed")
                    renderPicker()
                },
            ),
            onCancel = { overlayReturnFocus = back; renderPicker() },
        )
    }

    private fun randomStageName(): String {
        val taken = (queue.snapshot().map { it.singer } + listOfNotNull(queue.current?.singer)).map { it.lowercase() }.toSet()
        return STAGE_NAMES.shuffled().firstOrNull { it.lowercase() !in taken } ?: "Mystery Singer ${Random.nextInt(2, 99)}"
    }

    private fun confirmPicker(raw: String) {
        val song = pickerSong ?: return
        val name = cleanName(raw)
        if (name == null) {
            val input = findViewById<EditText>(R.id.pickerInput)
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
            return
        }
        if (pickerDuet) {
            val first = duetFirst
            if (first == null) {            // first of two: now ask for the partner
                duetFirst = name
                sfx.play(sfx.pop, 0.5f)
                renderPicker()
                return
            }
            if (first.equals(name, ignoreCase = true)) { toast("Pick someone else to sing with $first"); return }
        }
        val duet = pickerDuet
        val singer = if (duet) duetFirst + DUET_JOIN + name else name
        val singNow = mode == Mode.LOBBY && queue.isEmpty()
        val item = QueueItem(song, singer, fromPhone = false, duet = duet)
        val err = queue.add(item)
        closePicker()
        if (err != null) { toast(err); return }
        board.rememberPlayers(singer.split(DUET_JOIN))
        sfx.play(sfx.pop, 0.7f)
        if (singNow) {
            startNext()
        } else {
            toast("🎉 $singer ${if (duet) "are" else "is"} #${queue.positionOf(item.key)} in line!")
            if (queuePanel.isShown) panelListMode()
        }
    }

    private fun closePicker() {
        hideKeyboard()
        pickerLayer.visibility = View.GONE
        pickerSong = null
        restoreFocus()
    }

    // =====================================================================================
    // Intermission: "Next up!"
    // =====================================================================================

    private fun startNext() {
        stopListening()                           // the mic isn't needed once a song starts
        val item = queue.pollNext() ?: return goLobby()
        queue.current = item
        repairingKey = 0L
        mode = Mode.INTERMISSION
        hideAllOverlays()
        listOf(lobby, hud, queuePanel, scoreLayer).forEach { it.visibility = View.GONE }
        stage(false)
        stageBg.animating = true
        music.start()

        findViewById<TextView>(R.id.imAvatar).avatar(item.singer)
        findViewById<TextView>(R.id.imSinger).text = item.singer.uppercase()
        findViewById<TextView>(R.id.imSong).text = item.song.title
        updateIntermissionInfo()
        intermission.visibility = View.VISIBLE
        findViewById<View>(R.id.imContent).apply {
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.6f)).setDuration(500).start()
        }

        if (songsOnThisPlayer >= RECYCLE_PLAYER_AFTER) releasePlayer()
        ensurePlayer()
        cue(item.song.id)
        QueueStore.save(prefs, queue)
        countdown = 10
        ui.removeCallbacks(countdownTick)
        ui.post(countdownTick)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (mode != Mode.INTERMISSION || repairingKey != 0L) return
            if (countdown <= 0) { beginSinging(); return }
            val v = findViewById<TextView>(R.id.imCount)
            v.text = countdown.toString()
            v.scaleX = 1.3f; v.scaleY = 1.3f
            v.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
            sfx.play(sfx.tick, if (countdown <= 3) 1f else 0.4f)
            countdown--
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateIntermissionInfo() {
        val n = queue.size
        findViewById<TextView>(R.id.imQueueInfo).text =
            if (n == 0) "Last song in the queue. Add more! 📱" else "🎤 $n more waiting after this"
    }

    private fun singLater() {
        val item = queue.current ?: return
        if (queue.isEmpty()) { toast("Nobody else is waiting. ${item.singer}, you're up! 🎤"); return }
        queue.current = null
        queue.pushBack(item)
        toast("${item.singer} moved to the end of the queue")
        startNext()
    }

    // =====================================================================================
    // Singing
    // =====================================================================================

    private fun beginSinging() {
        val item = queue.current ?: return goLobby()
        if (repairingKey == item.key) return   // still looking for a version that plays
        ui.removeCallbacks(countdownTick)
        val p = player
        if (p == null || !playerReady) {
            startWhenReady = true
            findViewById<TextView>(R.id.imCount).text = "…"
            return
        }
        startWhenReady = false
        mode = Mode.SINGING
        intermission.visibility = View.GONE
        stage(true)
        stageBg.animating = false
        music.stop()
        duration = 0f
        maxSecond = 0f
        hud.visibility = View.VISIBLE
        findViewById<TextView>(R.id.nowAvatar).avatar(item.singer)
        findViewById<TextView>(R.id.nowSinger).text = item.singer
        findViewById<TextView>(R.id.nowSong).text = item.song.title
        updateNextPill()
        showStageInfo()
        songsOnThisPlayer++
        p.setVolume(volume)
        p.loadVideo(item.song.id, 0f)
    }

    /**
     * Turns off subtitles/captions. YouTube's player has no option to forbid them, so its
     * captions module is unloaded through the player page's script once the video plays.
     */
    private fun hideCaptions() {
        val web = playerView?.let { findWebView(it) } ?: return
        runCatching { web.evaluateJavascript(HIDE_CAPTIONS_JS, null) }
    }

    private val hideCaptionsLater = Runnable { hideCaptions() }

    private fun findWebView(v: View): android.webkit.WebView? {
        if (v is android.webkit.WebView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findWebView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** "🎤 Big finish, MARIA!" for FINALE_MS from the last second of the song, then the score. */
    private fun startFinale() {
        if (mode != Mode.SINGING || finale) return
        finale = true
        if (queuePanel.isShown) closePanel()
        updateCurtain()
        ui.removeCallbacks(finaleDone)
        ui.postDelayed(finaleDone, FINALE_MS)
    }

    private val finaleDone = Runnable { if (mode == Mode.SINGING && finale) showScore(1.0) }

    /** The lobby music comes back gently a few seconds after the score has landed. */
    private val scoreMusic = Runnable { if (mode == Mode.SCORE) music.start(fadeMs = 3000) }

    /** Shows or hides the video area (and the tap catcher above it). */
    private fun stage(on: Boolean) {
        stageContainer.visibility = if (on) View.VISIBLE else View.INVISIBLE
        stageTouch.visibility = if (on && isTouch) View.VISIBLE else View.GONE
        finale = false
        ui.removeCallbacks(finaleDone)
        isPaused = false
        if (!on) stageCurtain.visibility = View.GONE
    }

    /**
     * YouTube shows video suggestions over a paused video and in the last seconds of many videos.
     * Neither can be switched off, so the video is covered at those moments (the sound continues).
     */
    private fun updateCurtain() {
        val paused = mode == Mode.SINGING && isPaused && !finale
        val show = mode == Mode.SINGING && (paused || finale)
        val singer = queue.current?.singer.orEmpty()
        if (show) {
            findViewById<TextView>(R.id.curtainTitle).text =
                if (paused) "❚❚  PAUSED" else "🎤 Big finish${if (singer.isNotEmpty()) ", " + singer.uppercase() else ""}!"
            findViewById<TextView>(R.id.curtainSub).text = when {
                paused && isTouch -> "Tap to keep singing"
                paused -> "Press OK to keep singing"
                else -> "The score is coming…"
            }
        }
        if (show && stageCurtain.visibility != View.VISIBLE) {
            stageCurtain.alpha = 0f
            stageCurtain.visibility = View.VISIBLE
            stageCurtain.animate().alpha(1f).setDuration(if (paused) 150 else 700).start()
        } else if (!show && stageCurtain.visibility == View.VISIBLE) {
            stageCurtain.animate().cancel()
            stageCurtain.visibility = View.GONE
        }
    }

    // ---- touch (phones and tablets) ----

    private fun setUpTouch() {
        isTouch = resources.configuration.touchscreen == android.content.res.Configuration.TOUCHSCREEN_FINGER
        // Tap anywhere on "Next up" to start; buttons for the other choices
        intermission.setOnClickListener { if (mode == Mode.INTERMISSION) beginSinging() }
        click(R.id.imStart) { if (mode == Mode.INTERMISSION) beginSinging() }
        click(R.id.imLater) { if (mode == Mode.INTERMISSION) singLater() }
        click(R.id.imLobby) { if (mode == Mode.INTERMISSION) intermissionToLobby() }
        // Tap the score to move on
        scoreLayer.setOnClickListener { if (mode == Mode.SCORE && scoreDone) afterSong() }
        // While singing: tap the video for controls, tap the cover to resume
        stageTouch.setOnClickListener { onStageTap() }
        stageCurtain.setOnClickListener {
            when {
                mode != Mode.SINGING -> Unit
                finale -> showScore(1.0)                 // tap the "Big finish" screen: score now
                isPaused -> player?.play()
                else -> onStageTap()
            }
        }
        click(R.id.tbPause) { togglePause(); showStageInfo() }
        findViewById<SeekBar>(R.id.tbVolume).apply {
            progress = volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) setVolumeTo(value, save = false)   // saved once, when the finger lifts
                }
                override fun onStartTrackingTouch(bar: SeekBar) { ui.removeCallbacks(hideStageInfoLater) }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    setVolumeTo(bar.progress)
                    showStageInfo()
                }
            })
        }
        click(R.id.tbQueue) { hideStageInfo.run(); openPanel() }
        click(R.id.tbMenu) { hideStageInfo.run(); stageMenu() }
        if (isTouch) {
            findViewById<View>(R.id.imHint).visibility = View.GONE
            findViewById<View>(R.id.imTouch).visibility = View.VISIBLE
            // The touch bar takes the hint's place; the volume meter moves above it
            findViewById<View>(R.id.stageHint).visibility = View.GONE
            findViewById<View>(R.id.volumeHud).let { v ->
                (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = px(104)
                v.requestLayout()
            }
        }
    }

    private fun onStageTap() {
        if (mode != Mode.SINGING) return
        when {
            queuePanel.isShown -> closePanel()
            findViewById<View>(R.id.touchBar).isShown -> hideStageInfo.run()
            else -> showStageInfo()
        }
    }

    private fun intermissionToLobby() {
        queue.current?.let { queue.pushFront(it) }
        queue.current = null
        goLobby()
    }

    private fun updateNextPill() {
        val next = queue.peekNext()
        val pill = findViewById<View>(R.id.nextPill)
        if (next == null) { pill.visibility = View.GONE; return }
        pill.visibility = View.VISIBLE
        findViewById<TextView>(R.id.nextSinger).text = next.singer
        findViewById<TextView>(R.id.nextAvatar).avatar(next.singer)
    }

    private val hideStageInfo: Runnable = Runnable {
        ui.removeCallbacks(hideStageInfoLater)
        listOf(R.id.nowPill, R.id.nextPill, R.id.stageHint).forEach {
            findViewById<View>(it).animate().alpha(0f).setDuration(600).start()
        }
        // Invisible buttons must not catch taps, so the bar is removed, not just faded
        findViewById<View>(R.id.touchBar).visibility = View.GONE
    }
    private val hideStageInfoLater: Runnable = Runnable { hideStageInfo.run() }

    private fun showStageInfo() {
        ui.removeCallbacks(hideStageInfoLater)
        listOf(R.id.nowPill, R.id.nextPill, R.id.stageHint).forEach {
            findViewById<View>(it).animate().alpha(1f).setDuration(200).start()
        }
        if (isTouch) findViewById<View>(R.id.touchBar).visibility = View.VISIBLE
        ui.postDelayed(hideStageInfoLater, 7000)
    }

    private val watched: Double
        get() = if (duration > 0f) (maxSecond / duration).toDouble().coerceIn(0.0, 1.0) else 0.0

    private fun changeVolume(delta: Int) = setVolumeTo(volume + delta)

    /**
     * The party's one volume, 0-100: songs, lobby music, countdown and score sounds all follow it.
     * Separate from the TV's own volume (and from the microphones, which never pass through here).
     */
    private fun setVolumeTo(value: Int, save: Boolean = true) {
        volume = value.coerceIn(0, 100)
        if (save) prefs.edit().putInt("music_volume", volume).apply()
        player?.setVolume(volume)
        sfx.master = volume / 100f
        updateMusicButton()
        if (volumeLayer.isShown) findViewById<TextView>(R.id.volumeValue).text = "$volume%"
        findViewById<SeekBar>(R.id.tbVolume).let { if (it.progress != volume) it.progress = volume }
        findViewById<ProgressBar>(R.id.volumeBar).progress = volume
        findViewById<TextView>(R.id.volumeText).text = "$volume%"
        val hudView = findViewById<View>(R.id.volumeHud)
        ui.removeCallbacks(hideVolume)
        hudView.animate().alpha(1f).setDuration(120).start()
        ui.postDelayed(hideVolume, 2000)
    }

    private val hideVolume = Runnable { findViewById<View>(R.id.volumeHud).animate().alpha(0f).setDuration(400).start() }

    private fun togglePause() {
        if (finale) { showScore(1.0); return }
        val p = player ?: return
        if (isPlaying) p.pause() else p.play()
    }

    private fun stageMenu() {
        player?.pause()
        val f = watched
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts += "▶ Keep singing" to { player?.play(); Unit }
        if (f >= 0.25) opts += "🏁 Score me!" to { showScore(f) }
        opts += "⏭ Skip" to { afterSong() }
        opts += "🏠 Lobby" to { goLobby() }
        showSheet(
            "Done singing?",
            if (f >= 0.25) "You're ${(f * 100).toInt()}% through. Stopping early costs points!"
            else "Sing at least a quarter of the song to get a score.",
            opts,
            onCancel = { player?.play() }
        )
    }

    // ---------- queue panel (during a song) ----------

    private fun openPanel() {
        queuePanel.visibility = View.VISIBLE
        panelListMode()
        val inner = (queuePanel as ViewGroup).getChildAt(0)
        inner.translationX = px(380).toFloat()
        inner.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun panelListMode() {
        findViewById<View>(R.id.panelSearchMode).visibility = View.GONE
        findViewById<View>(R.id.panelListMode).visibility = View.VISIBLE
        renderQueueList(findViewById(R.id.panelQueueList), queue.snapshot(), 30, fallback = findViewById(R.id.btnPanelAdd))
        val list = findViewById<LinearLayout>(R.id.panelQueueList)
        (list.getChildAt(0)?.takeIf { it.isFocusable } ?: findViewById(R.id.btnPanelAdd)).requestFocus()
    }

    private fun panelSearchMode() {
        findViewById<View>(R.id.panelListMode).visibility = View.GONE
        findViewById<View>(R.id.panelSearchMode).visibility = View.VISIBLE
        if (!panelBrowser.hasResults()) panelBrowser.runQuickPick(0)
        findViewById<LinearLayout>(R.id.panelChips).getChildAt(0)?.requestFocus()
    }

    private fun panelBack() {
        if (findViewById<View>(R.id.panelSearchMode).isShown) panelListMode() else closePanel()
    }

    private fun closePanel() {
        hideKeyboard()
        queuePanel.visibility = View.GONE
        panelBrowser.clear()
    }

    // =====================================================================================
    // Score
    // =====================================================================================

    private fun showScore(fraction: Double) {
        ui.removeCallbacks(finaleDone)
        val item = queue.current ?: return goLobby()
        val score = Judge.score(fraction) ?: return afterSong()
        mode = Mode.SCORE
        scoreDone = false
        player?.pause()
        hideAllOverlays()
        hud.visibility = View.GONE
        queuePanel.visibility = View.GONE
        stage(false)
        stageBg.animating = true

        findViewById<TextView>(R.id.scAvatar).avatar(item.singer)
        findViewById<TextView>(R.id.scSinger).text = "${item.singer.uppercase()}'S SCORE"
        findViewById<TextView>(R.id.scSong).text = item.song.title
        val scoreView = findViewById<TextView>(R.id.scScore)
        scoreView.text = "--"
        listOf(R.id.scStars, R.id.scVerdict, R.id.scRank, R.id.scNext).forEach {
            findViewById<View>(it).visibility = View.INVISIBLE
        }
        scoreLayer.visibility = View.VISIBLE
        popIn(scoreLayer)

        // 1) drumroll with shuffling numbers
        sfx.play(sfx.drumroll)
        val started = System.currentTimeMillis()
        val shuffle = object : Runnable {
            override fun run() {
                if (mode != Mode.SCORE) return
                if (System.currentTimeMillis() - started >= 2600) { countUp(score, item); return }
                scoreView.text = Random.nextInt(10, 100).toString()
                scoreView.alpha = 0.5f + Random.nextFloat() * 0.5f
                ui.postDelayed(this, 70)
            }
        }
        ui.postDelayed(shuffle, 250)
    }

    private fun countUp(target: Int, item: QueueItem) {
        sfx.stop(sfx.drumroll)
        val scoreView = findViewById<TextView>(R.id.scScore)
        scoreView.alpha = 1f
        var last = -1
        scoreAnim = ValueAnimator.ofInt(0, target).apply {
            duration = 1500
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                val v = it.animatedValue as Int
                if (v != last) {
                    last = v
                    scoreView.text = v.toString()
                    if (v % 3 == 0) sfx.play(sfx.tick, 0.35f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && mode == Mode.SCORE) land(target, item)
                }
            })
            start()
        }
    }

    private fun land(score: Int, item: QueueItem) {
        val scoreView = findViewById<TextView>(R.id.scScore)
        scoreView.text = score.toString()
        ui.removeCallbacks(scoreMusic)
        ui.postDelayed(scoreMusic, SCORE_MUSIC_DELAY_MS)
        sfx.play(sfx.reveal)
        if (score >= 80) sfx.play(sfx.applause, if (score >= 90) 0.9f else 0.6f)
        scoreView.scaleX = 1.35f; scoreView.scaleY = 1.35f
        scoreView.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(450).start()

        val stars = Judge.stars(score)
        findViewById<TextView>(R.id.scStars).text = "★".repeat(stars) + "☆".repeat(5 - stars)
        findViewById<TextView>(R.id.scVerdict).text = Judge.verdict(score)

        val rank = board.add(Entry(item.singer, score, item.song.title, System.currentTimeMillis(), duet = item.duet))
        val of = if (item.duet) "duet tonight" else "tonight"
        findViewById<TextView>(R.id.scRank).text = when (rank) {
            1 -> "👑 #1 $of!"
            2 -> "🥈 #2 $of"
            3 -> "🥉 #3 $of"
            else -> "#$rank $of"
        }
        listOf(R.id.scStars, R.id.scVerdict, R.id.scRank).forEachIndexed { i, id ->
            findViewById<View>(id).apply {
                visibility = View.VISIBLE
                alpha = 0f; translationY = px(16).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(150L + i * 150L).setDuration(380).start()
            }
        }

        when {
            score >= 95 -> {
                sfx.play(sfx.fanfare)
                confetti.burst(240)
                ui.postDelayed({ if (mode == Mode.SCORE) confetti.burst(140) }, 900)
            }
            score >= 90 -> confetti.burst(90)
        }

        ui.postDelayed({
            if (mode != Mode.SCORE) return@postDelayed
            scoreDone = true
            autoNextIn = 12
            updateScoreNext()
            findViewById<View>(R.id.scNext).apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(300).start()
            }
            ui.postDelayed(scoreAutoNext, 1000)
        }, 1600)
    }

    private val scoreAutoNext = object : Runnable {
        override fun run() {
            if (mode != Mode.SCORE) return
            autoNextIn--
            if (autoNextIn <= 0) { afterSong(); return }
            updateScoreNext()
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateScoreNext() {
        val next = queue.peekNext()
        findViewById<TextView>(R.id.scNext).text =
            if (next != null) "Next up: ${next.singer}   ·   ${if (isTouch) "Tap" else "OK"} to continue ($autoNextIn)"
            else "Queue is empty   ·   ${if (isTouch) "Tap" else "OK"} for the lobby ($autoNextIn)"
    }

    /** Song finished, skipped or failed: move on to the next singer or back to the lobby. */
    private fun afterSong() {
        ui.removeCallbacks(scoreAutoNext)
        scoreAnim?.cancel()
        sfx.stop(sfx.drumroll)
        scoreLayer.visibility = View.GONE
        queue.current = null
        QueueStore.save(prefs, queue)
        if (queue.isEmpty()) goLobby() else startNext()
    }

    // =====================================================================================
    // YouTube player (created for the party, released in the lobby)
    // =====================================================================================

    private fun ensurePlayer() {
        if (playerView != null) return
        val v = layoutInflater.inflate(R.layout.stage_player, stageContainer, false) as YouTubePlayerView
        stageContainer.addView(v)
        playerView = v
        playerReady = false
        songsOnThisPlayer = 0
        val options = IFramePlayerOptions.Builder(this)
            .controls(0)       // the remote drives everything
            .rel(0)
            .ivLoadPolicy(3)   // no annotations
            .ccLoadPolicy(0)   // no captions on top of the lyrics
            .build()
        v.initialize(PlayerListener(v), options)
    }

    private fun cue(id: String) {
        pendingCue = id
        if (playerReady) player?.cueVideo(id, 0f)
    }

    private fun releasePlayer() {
        val v = playerView ?: return
        playerView = null
        player = null
        playerReady = false
        startWhenReady = false
        pendingCue = null
        isPlaying = false
        runCatching { v.release() }
        stageContainer.removeView(v)
    }

    /** One listener per player: events from a player that has since been replaced are ignored. */
    private inner class PlayerListener(private val view: YouTubePlayerView) : AbstractYouTubePlayerListener() {
        private val current get() = playerView === view

        override fun onReady(youTubePlayer: YouTubePlayer) {
            if (!current) return
            player = youTubePlayer
            playerReady = true
            youTubePlayer.setVolume(volume)
            pendingCue?.let { youTubePlayer.cueVideo(it, 0f) }
            if (startWhenReady && mode == Mode.INTERMISSION) beginSinging()
        }

        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
            if (!current) return
            isPlaying = state == PlayerConstants.PlayerState.PLAYING
            isPaused = state == PlayerConstants.PlayerState.PAUSED
            if (mode != Mode.SINGING) {
                if (isPlaying) youTubePlayer.pause()
                return
            }
            when (state) {
                PlayerConstants.PlayerState.PLAYING -> {
                    youTubePlayer.setVolume(volume)
                    // Captions can switch on by themselves (TV caption setting, another language):
                    // turn them off now and once more after YouTube finishes loading them
                    hideCaptions()
                    ui.removeCallbacks(hideCaptionsLater)
                    ui.postDelayed(hideCaptionsLater, 2500)
                }
                PlayerConstants.PlayerState.ENDED -> startFinale()   // if the last second wasn't caught
                else -> Unit
            }
            updateCurtain()
        }

        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
            if (!current) return
            if (mode == Mode.SINGING && duration > 0f) this@MainActivity.duration = duration
        }

        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
            if (!current) return
            if (mode == Mode.SINGING && second > maxSecond) maxSecond = second
            // The very last second: "Big finish!" takes over (and hides YouTube's end-of-video suggestions)
            if (mode == Mode.SINGING && !finale && duration > 30f && second >= duration - FINALE_LEAD_S) startFinale()
        }

        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
            if (!current) return
            val item = queue.current ?: return
            if (mode != Mode.INTERMISSION && mode != Mode.SINGING) return
            pendingCue = null
            // Remember refusals so the video (and, after repeats, its channel) stops showing up
            if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ||
                error == PlayerConstants.PlayerError.VIDEO_NOT_FOUND
            ) search.markUnplayable(item.song)
            tryAnotherVersion(item)
        }
    }

    /**
     * A video refused to play (usually its owner blocks playback outside YouTube). Instead of
     * skipping the singer, find another karaoke video of the same song and play that.
     */
    private fun tryAnotherVersion(item: QueueItem) {
        if (repairingKey == item.key) return
        val failed = item.song
        if (item.retries >= MAX_VERSION_TRIES) {
            toast("😕 No version of \"${failed.title.take(40)}\" plays here. Skipping…")
            afterSong()
            return
        }
        repairingKey = item.key
        // Back to the "Next up" screen while we look
        if (mode == Mode.SINGING) {
            mode = Mode.INTERMISSION
            listOf(hud, queuePanel).forEach { it.visibility = View.GONE }
            stage(false)
            intermission.visibility = View.VISIBLE
            stageBg.animating = true
            music.start()
        }
        ui.removeCallbacks(countdownTick)
        findViewById<TextView>(R.id.imCount).text = "…"
        toast("🔁 That video is blocked by its owner. Finding another version…")
        val started = runCatching {
            io.execute {
                val alt = runCatching { search.alternativeFor(failed, setOf(failed.id)) }.getOrNull()
                ui.post { onAnotherVersion(item, alt) }
            }
        }.isSuccess
        if (!started) repairingKey = 0L
    }

    private fun onAnotherVersion(item: QueueItem, alt: Song?) {
        if (isFinishing || isDestroyed || repairingKey != item.key) return
        repairingKey = 0L
        // The singer may have been sent back to the lobby or the end of the queue meanwhile
        if (queue.current?.key != item.key || mode != Mode.INTERMISSION) return
        if (alt == null) {
            toast("😕 No other version of \"${item.song.title.take(40)}\" found. Skipping…")
            afterSong()
            return
        }
        val fixed = item.copy(song = alt, retries = item.retries + 1)
        queue.current = fixed
        findViewById<TextView>(R.id.imSong).text = alt.title
        toast("🎶 Found another version: ${alt.channel.ifEmpty { "YouTube" }.take(30)}")
        cue(alt.id)
        countdown = 3
        ui.post(countdownTick)
    }

    // =====================================================================================
    // Phones: tiny local web server + QR code
    // =====================================================================================

    private fun startServer() {
        if (server != null) return
        // A new party key after 12 hours (only between parties, so phones already joined keep working):
        // guests from an old party can't keep adding songs whenever they're on the Wi-Fi.
        val now = System.currentTimeMillis()
        val stale = now - prefs.getLong("party_key_time", 0L) > 12 * 3600_000L && queue.isEmpty() && queue.current == null
        val token = prefs.getString("party_key", null)?.takeIf { !stale }
            ?: newToken().also { prefs.edit().putString("party_key", it).putLong("party_key_time", now).apply() }
        val page = runCatching { assets.open("remote.html").use { it.readBytes() } }.getOrNull() ?: return
        val s = PartyServer(PartyBackend(queue, search, board, this), token, page)
        server = s
        io.execute {
            val port = s.start()
            val ip = lanAddress()
            val url = if (port > 0 && ip != null) "http://$ip:$port/?k=$token" else null
            ui.post {
                if (server === s) {
                    partyUrl = url
                    updateQr()
                }
            }
        }
    }

    /** Synchronous on purpose: closing a socket is instant, and a queued task could be dropped on exit. */
    private fun stopServer() {
        val s = server ?: return
        server = null
        s.stop()
    }

    private fun lanAddress(): String? = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun updateQr() {
        val url = partyUrl
        listOf(R.id.lobbyQr, R.id.panelQr, R.id.imQr, R.id.bigQr).forEach { findViewById<QrView>(it).setText(url) }
        findViewById<TextView>(R.id.bigQrUrl).text = url?.let { "Or type in the browser:\n" + it } ?: ""
        findViewById<TextView>(R.id.lobbyUrl).text =
            if (url != null) url.substringAfter("http://").substringBefore("/?") + "\nSame Wi-Fi as the TV"
            else "Connect the TV to your home network to let phones join"
    }

    override fun onPhoneAdded(item: QueueItem) {
        sfx.play(sfx.pop)
        toast("📱 ${item.singer} added “${item.song.title.take(45)}”")
    }

    override fun onPlayersChanged() {
        if (boardLayer.isShown) boardShows?.let { boardShows = null; renderBoard(it) }
        // Only the remove list is redrawn: elsewhere a redraw would clear a name being typed
        if (pickerLayer.isShown && pickerRemove && !sheetLayer.isShown) renderPicker()
    }

    private fun showBigQr() {
        if (partyUrl == null) { toast("Connect the TV to your home Wi-Fi or network first"); return }
        overlayReturnFocus = currentFocus
        qrLayer.visibility = View.VISIBLE
        lockFocus()
        popIn(qrLayer)
        findViewById<View>(R.id.bigQrClose).requestFocus()
    }

    private fun hideBigQr() {
        qrLayer.visibility = View.GONE
        restoreFocus()
    }

    // =====================================================================================
    // Party country: every country, type to filter
    // =====================================================================================

    /** Quick picks and the flag button follow the chosen country. */
    private fun applyCountry() {
        val c = search.country
        val picks = Countries.picks(c)
        lobbyBrowser.setPicks(picks)
        panelBrowser.setPicks(picks)
        findViewById<TextView>(R.id.btnCountry).text = c.flag
    }

    private fun showCountries() {
        overlayReturnFocus = currentFocus
        findViewById<EditText>(R.id.countryFilter).setText("")
        countryLayer.visibility = View.VISIBLE
        popIn(countryLayer)
        lockFocus()
        renderCountries("", focus = true)
    }

    private fun hideCountries() {
        countryLayer.visibility = View.GONE
        hideKeyboard()
        findViewById<LinearLayout>(R.id.countryRows).removeAllViews()   // a few hundred views: free them
        restoreFocus()
    }

    private fun renderCountries(filter: String, focus: Boolean) {
        val rows = findViewById<LinearLayout>(R.id.countryRows)
        rows.removeAllViews()
        val current = search.country
        var selected: View? = null
        var count = 0

        fun grid(list: List<Country>) {
            list.chunked(4).forEach { chunk ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEach { c ->
                    val b = countryButton(c, c.code == current.code)
                    if (c.code == current.code && selected == null) selected = b
                    row.addView(b, LinearLayout.LayoutParams(0, px(46), 1f).apply { setMargins(px(5), px(5), px(5), px(5)) })
                    count++
                }
                repeat(4 - chunk.size) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(px(5), 0, px(5), 0) })
                }
                rows.addView(row)
            }
        }
        fun heading(text: String) = rows.addView(label(text, 13f, R.color.neon_cyan, bold = true).apply {
            letterSpacing = 0.15f
            setPadding(px(6), px(8), 0, px(2))
        })

        if (filter.isBlank()) {
            val suggested = listOfNotNull(Countries.INTERNATIONAL, current.takeIf { !it.isInternational }, Countries.device())
                .distinctBy { it.code }
            heading("SUGGESTED")
            grid(suggested)
            heading("ALL COUNTRIES")
            grid(Countries.all())
        } else {
            val world = listOf("international", "world", "global")
            val f = filter.trim().lowercase()
            val list = (if (world.any { it.startsWith(f) }) listOf(Countries.INTERNATIONAL) else emptyList()) +
                Countries.all().filter { Countries.matches(it, filter) }
            grid(list)
        }
        findViewById<View>(R.id.countryEmpty).visibility = if (count == 0) View.VISIBLE else View.GONE
        findViewById<ScrollView>(R.id.countryScroll).scrollTo(0, 0)
        if (focus) (selected ?: firstCountryButton())?.requestFocus()
    }

    private fun firstCountryButton(): View? {
        val rows = findViewById<LinearLayout>(R.id.countryRows)
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i) as? LinearLayout ?: continue
            row.getChildAt(0)?.takeIf { it.isFocusable }?.let { return it }
        }
        return null
    }

    private fun countryButton(c: Country, isCurrent: Boolean) = TextView(this).apply {
        text = "${c.flag}  ${c.name}"
        textSize = 15f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(getColor(R.color.text_main))
        setBackgroundResource(R.drawable.chip_bg)
        setPadding(px(14), 0, px(10), 0)
        isFocusable = true
        isClickable = true
        isSelected = isCurrent
        if (isCurrent) paint.isFakeBoldText = true
        setOnClickListener { chooseCountry(c) }
    }

    private fun chooseCountry(c: Country) {
        val changed = c.code != search.country.code
        search.country = c
        prefs.edit().putString("country", c.code).apply()
        hideCountries()
        if (!changed) return
        applyCountry()
        panelBrowser.clear()                    // reloads with the new picks next time it opens
        lobbyBrowser.runQuickPick(0)
        toast(if (c.isInternational) "🌍 International mix" else "${c.flag} ${c.name}: music from there comes first")
    }

    // ---- volume pop-up (the 🎵 button) ----

    private fun showVolumePanel() {
        overlayReturnFocus = currentFocus
        updateVolumePanel()
        findViewById<TextView>(R.id.volumeHint).text = if (isTouch) "Drag to change" else "◀ ▶ to change"
        volumeLayer.visibility = View.VISIBLE
        popIn(volumeLayer)
        lockFocus()
        findViewById<View>(R.id.volumeSlider).requestFocus()
    }

    private fun updateVolumePanel() {
        findViewById<TextView>(R.id.volumeValue).text = "$volume%"
        findViewById<SeekBar>(R.id.volumeSlider).progress = volume
        findViewById<TextView>(R.id.volumeMusic).text = if (music.enabled) "🎵 Lobby music: On" else "🔈 Lobby music: Off"
    }

    private fun hideVolumePanel() {
        volumeLayer.visibility = View.GONE
        setVolumeTo(volume)                       // save
        restoreFocus()
    }

    // ---- voice search (remote's search/mic button or the 🎙 button) ----

    private var recognizer: android.speech.SpeechRecognizer? = null

    /**
     * Listens with Android's speech recognizer inside the app (on TVs, through the remote's
     * microphone), showing the words in the search box as they're heard. Asks for microphone
     * permission the first time. Falls back to the system's voice screen if there's no recognizer.
     */
    private fun voiceSearch() {
        if (recognizer != null) { stopListening(); return }          // pressed again: stop
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { voiceSearchViaApp(); return }
        listen()
    }

    @Deprecated("Platform permission callback; fine for a single permission")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(this)) listen() else voiceSearchViaApp()
        } else {
            toast("🎙 Voice search needs the microphone. Type instead 👇")
            focusSearchBox()
        }
    }

    private fun listen() {
        val r = runCatching { android.speech.SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
            ?: return voiceSearchViaApp()
        recognizer = r
        val box = findViewById<EditText>(R.id.lobbySearch)
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                findViewById<View>(R.id.btnVoice).isSelected = true
                box.setText("")
                box.hint = "🎙 Listening… say a song or artist"
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { box.setText(it.take(80)) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                stopListening()
                if (!text.isNullOrEmpty() && mode == Mode.LOBBY) lobbyBrowser.searchFor(text.take(80))
            }
            override fun onError(error: Int) {
                stopListening()
                toast(when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                    android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "🎙 Didn't catch that. Press 🎙 and try again"
                    android.speech.SpeechRecognizer.ERROR_NETWORK,
                    android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "🎙 Voice search needs the internet. Type instead"
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "🎙 Voice search needs the microphone"
                    else -> "🎙 Voice search isn't working right now. Type instead"
                })
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        runCatching { r.startListening(intent) }.onFailure { stopListening(); voiceSearchViaApp() }
    }

    private fun stopListening() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        findViewById<View>(R.id.btnVoice).isSelected = false
        findViewById<EditText>(R.id.lobbySearch).hint = getString(R.string.search_hint)
    }

    private fun focusSearchBox() {
        val box = findViewById<EditText>(R.id.lobbySearch)
        box.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(box, 0)
    }

    /** The system's own voice screen (devices without an in-app recognizer). */
    private fun voiceSearchViaApp() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song or an artist")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            toast("Voice search isn't available here. Type instead 👇")
            focusSearchBox()
        }
    }

    // =====================================================================================
    // Leaderboard overlay
    // =====================================================================================

    private fun showBoard() {
        overlayReturnFocus = currentFocus
        boardLayer.visibility = View.VISIBLE
        popIn(boardLayer)
        boardShows = null
        renderBoard(BoardView.TONIGHT)
        lockFocus()
        findViewById<View>(R.id.tabTonight).requestFocus()
    }

    private fun hideBoard() {
        boardLayer.visibility = View.GONE
        restoreFocus()
    }

    private enum class BoardView { TONIGHT, ALL, DUETS }
    private var boardShows: BoardView? = null

    private fun renderBoard(view: BoardView) {
        if (boardShows == view && boardLayer.isShown && findViewById<LinearLayout>(R.id.boardRows).childCount > 0) return
        boardShows = view
        findViewById<View>(R.id.tabTonight).isSelected = view == BoardView.TONIGHT
        findViewById<View>(R.id.tabAll).isSelected = view == BoardView.ALL
        findViewById<View>(R.id.tabDuets).isSelected = view == BoardView.DUETS
        val entries = when (view) {
            BoardView.TONIGHT -> board.tonight()
            BoardView.ALL -> board.allTime()
            // Duets: tonight's if there are any, otherwise the best of all time
            BoardView.DUETS -> board.tonight(duet = true).ifEmpty { board.allTime(duet = true) }
        }
        val list = board.top(entries, 8)   // top 8 fits any screen
        findViewById<View>(R.id.boardEmpty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        // Podium: 2nd, 1st, 3rd
        val podium = findViewById<LinearLayout>(R.id.podium)
        podium.removeAllViews()
        listOf(1, 0, 2).forEach { i ->
            val e = list.getOrNull(i) ?: return@forEach
            podium.addView(podiumColumn(e, i), LinearLayout.LayoutParams(px(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val rows = findViewById<LinearLayout>(R.id.boardRows)
        rows.removeAllViews()
        list.drop(3).forEachIndexed { j, e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(3), px(14), px(3))
                setBackgroundColor(if (j % 2 == 0) 0x26FFFFFF else 0x10FFFFFF)
            }
            row.addView(label("${j + 4}.", 16f, R.color.text_dim), LinearLayout.LayoutParams(px(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply { avatar(e.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt()); textSize = 13f },
                LinearLayout.LayoutParams(px(28), px(28)))
            row.addView(label(e.name, 17f, R.color.text_main, bold = true).apply { setPadding(px(10), 0, 0, 0) },
                LinearLayout.LayoutParams(px(if (e.duet) 250 else 170), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(label(e.song, 12f, R.color.text_dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(e.score.toString(), 20f, R.color.gold, bold = true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(60), ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row)
        }
    }

    private fun podiumColumn(e: Entry, place: Int): View {
        val heights = intArrayOf(72, 52, 38)
        val medals = arrayOf("🥇", "🥈", "🥉")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                avatar(e.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt())
                textSize = 24f; paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(px(46), px(46)))
            addView(label(e.name, 17f, R.color.text_main, bold = true).apply { gravity = Gravity.CENTER })
            addView(label(e.score.toString(), 26f, R.color.gold, bold = true).apply { gravity = Gravity.CENTER })
            addView(TextView(context).apply {
                text = medals[place]
                textSize = 30f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(colorFor(e.name) and 0x00FFFFFF or 0x99000000.toInt(), 0x331A1030)
                ).apply { setCornerRadii(floatArrayOf(px(12).toFloat(), px(12).toFloat(), px(12).toFloat(), px(12).toFloat(), 0f, 0f, 0f, 0f)) }
            }, LinearLayout.LayoutParams(px(140), px(heights[place])).apply { topMargin = px(6) })
        }
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(getColor(color))
        if (bold) paint.isFakeBoldText = true
    }

    // =====================================================================================
    // Generic overlays: action sheet, toast
    // =====================================================================================

    private fun showSheet(title: String, text: String, options: List<Pair<String, () -> Unit>>, onCancel: () -> Unit = {}) {
        if (!sheetLayer.isShown) overlayReturnFocus = currentFocus
        findViewById<TextView>(R.id.sheetTitle).text = title
        findViewById<TextView>(R.id.sheetText).apply {
            this.text = text
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val box = findViewById<LinearLayout>(R.id.sheetButtons)
        box.removeAllViews()
        options.forEachIndexed { i, (label, action) ->
            val b = styledButton(label, primary = i == 0)
            b.setOnClickListener {
                sheetLayer.visibility = View.GONE
                restoreFocus()
                action()
            }
            box.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52)).apply {
                if (i > 0) marginStart = px(12)
            })
        }
        sheetCancel = onCancel
        sheetLayer.visibility = View.VISIBLE
        popIn(sheetLayer)
        lockFocus()
        box.getChildAt(0)?.requestFocus()
    }

    private fun cancelSheet() {
        sheetLayer.visibility = View.GONE
        restoreFocus()
        sheetCancel?.invoke()
        sheetCancel = null
    }

    private fun hideAllOverlays() {
        listOf(pickerLayer, sheetLayer, boardLayer, qrLayer, volumeLayer).forEach { it.visibility = View.GONE }
        if (countryLayer.isShown) hideCountries()
        hideKeyboard()
        lockFocus()
    }

    private fun anyOverlayOpen() = pickerLayer.isShown || sheetLayer.isShown || boardLayer.isShown || qrLayer.isShown ||
        countryLayer.isShown || volumeLayer.isShown

    /**
     * While a pop-up is open, nothing behind it can take the remote's focus: ▲ ▼ ◀ ▶ stay
     * inside the pop-up instead of wandering into the song list.
     */
    private fun lockFocus() {
        val block = anyOverlayOpen()
        val rule = if (block) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        listOf(lobby, queuePanel, hud, intermission).forEach { (it as? ViewGroup)?.descendantFocusability = rule }
        // Pop-ups stacked on another (a sheet over the picker) only keep the top one reachable
        val top = listOf(volumeLayer, sheetLayer, countryLayer, qrLayer, boardLayer, pickerLayer).firstOrNull { it.isShown }
        listOf(pickerLayer, boardLayer, qrLayer, countryLayer, sheetLayer, volumeLayer).forEach {
            (it as? ViewGroup)?.descendantFocusability =
                if (top == null || it === top) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun restoreFocus() {
        lockFocus()
        val v = overlayReturnFocus
        overlayReturnFocus = null
        if (v != null && v.isShown) v.requestFocus()
    }

    private val hideToast = Runnable {
        toastView.animate().alpha(0f).setDuration(300).withEndAction { toastView.visibility = View.GONE }.start()
    }

    private fun toast(msg: String) {
        toastView.text = msg
        toastView.visibility = View.VISIBLE
        toastView.alpha = 0f
        toastView.translationY = -px(20).toFloat()
        toastView.animate().alpha(1f).translationY(0f).setDuration(250).start()
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, 3500)
    }

    // =====================================================================================
    // Small helpers
    // =====================================================================================

    private fun styledButton(label: String, primary: Boolean) =
        TextView(this, null, 0, if (primary) R.style.TvButton_Primary else R.style.TvButton).apply { text = label }

    private fun popIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(180).start()
    }

    private fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action() }

    private fun hideKeyboard() {
        val v = currentFocus ?: window.decorView
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_VOICE = 42
        const val REQ_MIC = 43
        /** Unloads the YouTube player's captions; harmless if the page differs. */
        const val HIDE_CAPTIONS_JS = "(function(){try{var p=window.player;" +
            "if(p&&p.unloadModule){p.unloadModule('captions');p.unloadModule('cc');}" +
            "if(p&&p.setOption){p.setOption('captions','track',{});}}catch(e){}})();"
        const val AUTOSTART_MS = 30_000L
        /** Start a fresh YouTube player every few songs so its web page can't grow all night. */
        const val RECYCLE_PLAYER_AFTER = 6
        /** How many other versions to try when a video refuses to play. */
        const val MAX_VERSION_TRIES = 2
        /** "Big finish!" appears this many seconds before the end of the song… */
        const val FINALE_LEAD_S = 1f
        /** …and stays this long before the score. */
        const val FINALE_MS = 5000L
        /** The lobby music fades back in this long after the score lands. */
        const val SCORE_MUSIC_DELAY_MS = 5000L
        val STAGE_NAMES = listOf(
            "Disco Diva", "Rock Legend", "Mystery Voice", "Mic Master", "Golden Voice", "Pop Idol",
            "Shower Singer", "Night Owl", "Velvet Voice", "Funky Star", "Karaoke King", "Karaoke Queen",
            "Rockstar", "Dancing Queen", "Soul Sister", "Mr. Groove"
        )
    }
}
