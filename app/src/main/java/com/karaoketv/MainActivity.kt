package com.karaoketv

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
    private lateinit var history: History
    private lateinit var achievements: AchievementBook
    private lateinit var music: LobbyMusic
    private lateinit var sfx: Sfx
    private lateinit var cheers: Cheers

    // Layers
    private lateinit var stageBg: StageView
    private lateinit var stageContainer: FrameLayout
    private lateinit var lobby: View
    private lateinit var hud: View
    private lateinit var queuePanel: View
    private lateinit var intermission: View
    private lateinit var scoreLayer: View
    private lateinit var boardLayer: View
    private lateinit var aboutLayer: View
    private lateinit var achLayer: View
    private lateinit var pickerLayer: View
    private lateinit var sheetLayer: View
    private lateinit var qrLayer: View
    private lateinit var countryLayer: View
    private lateinit var volumeLayer: View
    private lateinit var recapLayer: View
    private lateinit var stageReactions: ReactionsView
    private lateinit var stageTouch: View
    private lateinit var stageCurtain: View
    private lateinit var toastView: TextView
    private lateinit var confetti: ConfettiView
    private lateinit var hypeBar: HypeBarView
    private lateinit var hypeLabel: TextView

    /** This song's crowd hype (see [Hype]). */
    private val hype = Hype()

    /** Tonight's look (Christmas, Halloween, Carnival or none). */
    private var theme = PartyTheme.NONE
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
    /** Sound effects (countdown, drumroll, score, pops), 0-100: its own slider since 3.20. */
    private var fxVolume = 80

    // Phones
    private var server: PartyServer? = null
    private var backend: PartyBackend? = null
    private var partyUrl: String? = null
    private var serverPort = 0
    private var partyToken: String? = null
    /** Internet (for YouTube) and the TV's address on the Wi-Fi (for the phones). */
    private lateinit var net: Connection
    private var wasOffline = false
    /** The current song is on hold until the internet is back (then it starts again from "Next up"). */
    private var waitingForNet = false

    // Misc state
    private var lastInteraction = System.currentTimeMillis()
    private var backPressedAt = 0L
    private var countdown = 0
    private var firstLobbyLoad = true
    private var scoreDone = false
    private var scoreAnim: ValueAnimator? = null
    private var autoNextIn = 0
    private var pickerSong: Song? = null
    /** The search or quick pick the picked song came from (helps tell its genre). */
    private var pickerFrom: String? = null
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
        fxVolume = prefs.getInt("fx_volume", volume)   // until set, the effects follow the old shared volume
        queue = PartyQueue(ui).also { it.onChanged = ::onQueueChanged }
        search = SongSearch(this).also {
            it.country = runCatching { Countries.byCode(prefs.getString("country", "")) }.getOrDefault(Countries.INTERNATIONAL)
        }
        board = Leaderboard(this)
        history = History(this)
        achievements = AchievementBook(this)
        // First run of this version: award achievements for the scores already saved (off the main thread)
        io.execute { runCatching { achievements.backfill(board.all()); achievements.backfillNew(board.all()) } }
        music = LobbyMusic(this)
        sfx = Sfx(this).also { it.master = fxVolume / 100f }
        cheers = Cheers(this)
        net = Connection(this, io) { onNetworkChanged() }

        stageBg = findViewById(R.id.stageBg)
        stageContainer = findViewById(R.id.stageContainer)
        lobby = findViewById(R.id.lobby)
        hud = findViewById(R.id.hud)
        queuePanel = findViewById(R.id.queuePanel)
        intermission = findViewById(R.id.intermission)
        scoreLayer = findViewById(R.id.scoreLayer)
        boardLayer = findViewById(R.id.boardLayer)
        aboutLayer = findViewById(R.id.aboutLayer)
        achLayer = findViewById(R.id.achLayer)
        pickerLayer = findViewById(R.id.pickerLayer)
        sheetLayer = findViewById(R.id.sheetLayer)
        qrLayer = findViewById(R.id.qrLayer)
        countryLayer = findViewById(R.id.countryLayer)
        volumeLayer = findViewById(R.id.volumeLayer)
        recapLayer = findViewById(R.id.recapLayer)
        stageReactions = findViewById(R.id.stageReactions)
        stageTouch = findViewById(R.id.stageTouch)
        stageCurtain = findViewById(R.id.stageCurtain)
        toastView = findViewById(R.id.toast)
        confetti = findViewById(R.id.confetti)
        hypeBar = findViewById(R.id.hypeBar)
        hypeLabel = findViewById(R.id.hypeLabel)

        lobbyBrowser = SongBrowser(
            this, search, io,
            findViewById(R.id.lobbySearch), findViewById(R.id.lobbyChips), findViewById(R.id.lobbyStatus),
            findViewById(R.id.lobbyScroll), findViewById(R.id.lobbyResults), columns = 3,
        ) { song, from -> openPicker(song, from) }
        lobbyBrowser.played = { history.all() }
        lobbyBrowser.onOffline = { net.suspect() }
        setUpDice(lobbyBrowser)
        lobbyBrowser.onResults = { found ->
            if (firstLobbyLoad && found && mode == Mode.LOBBY && !anyOverlayOpen()) lobbyBrowser.focusFirst()
            firstLobbyLoad = false
        }

        panelBrowser = SongBrowser(
            this, search, io,
            findViewById(R.id.panelSearch), findViewById(R.id.panelChips), findViewById(R.id.panelStatus),
            findViewById(R.id.panelScroll), findViewById(R.id.panelResults), columns = 1,
        ) { song, from -> openPicker(song, from) }
        panelBrowser.played = { history.all() }
        panelBrowser.onOffline = { net.suspect() }
        setUpDice(panelBrowser)

        click(R.id.btnBoard) { showBoard() }
        click(R.id.btnRecap) { showRecap() }
        click(R.id.settingsFair) { toggleFair() }
        click(R.id.recapDone) { hideRecap() }
        click(R.id.recapAgain) { recapGo(0) }
        // Phones: a tap anywhere moves the show on (the buttons at the end handle their own taps)
        recapLayer.setOnClickListener { if (!findViewById<View>(R.id.recapEnd).isShown) recapGo(recapStep + 1) }
        click(R.id.boardClose) { hideBoard() }
        click(R.id.achClose) { hideSinger() }
        click(R.id.settingsAbout) { hideSettings(); showAbout() }
        click(R.id.aboutClose) { hideAbout() }
        click(R.id.aboutOpen) { openProjectPage() }
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
        click(R.id.btnSettings) { showSettings() }
        click(R.id.volumeDone) { hideSettings() }
        click(R.id.volumeMusic) {
            music.enabled = !music.enabled
            updateVolumePanel()
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
        findViewById<SeekBar>(R.id.fxSlider).apply {
            keyProgressIncrement = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    fxVolume = value
                    sfx.master = value / 100f
                    findViewById<TextView>(R.id.fxValue).text = "$value%"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                // Let them hear it
                override fun onStopTrackingTouch(bar: SeekBar) { sfx.play(sfx.pop) }
            })
            // ◀ ▶ on the remote: a little pop at each step, at the new level
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) sfx.play(sfx.pop)
                false
            }
        }
        // The lobby music has its own slider (it plays under conversations, so usually much lower)
        findViewById<SeekBar>(R.id.lobbySlider).apply {
            keyProgressIncrement = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    music.volume = value
                    if (!music.enabled && value > 0) music.enabled = true   // moving it up turns the music on
                    updateVolumePanel()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        click(R.id.btnStart) { startNext() }
        click(R.id.btnPanelAdd) { panelSearchMode() }
        click(R.id.pickerGo) { pickerGo() }
        click(R.id.pickerCancel) { closePicker() }
        click(R.id.pickerSolo) { setPickerMode(duet = false) }
        click(R.id.pickerDuet) { setPickerMode(duet = true) }
        click(R.id.pickerRandom) { addNewPlayer(randomStageName()) }
        click(R.id.pickerRemove) {
            pickerRemove = !pickerRemove
            if (pickerRemove) pickerChosen.clear()
            clearPickerInput()
            renderPicker(focus = if (pickerRemove) R.id.pickerNames else R.id.pickerRemove)
        }
        click(R.id.pickerPrev) { pickerPage--; renderPickerNames(); findViewById<View>(R.id.pickerPrev).let { if (it.isEnabled) it.requestFocus() else findViewById<View>(R.id.pickerNext).requestFocus() } }
        click(R.id.pickerNext) { pickerPage++; renderPickerNames(); findViewById<View>(R.id.pickerNext).let { if (it.isEnabled) it.requestFocus() else findViewById<View>(R.id.pickerPrev).requestFocus() } }
        for (view in BoardView.values()) {
            click(view.tab) { renderBoard(view) }
            findViewById<View>(view.tab).setOnFocusChangeListener { _, f ->
                if (!f) return@setOnFocusChangeListener
                renderBoard(view)
                // Back on the tabs: show the top of the list (and the podium) again
                listOf(R.id.boardScroll, R.id.boardAchScroll).forEach { findViewById<ScrollView>(it).smoothScrollTo(0, 0) }
            }
        }
        // Typing a name filters the buttons: with dozens of players, yours is a few letters away
        findViewById<EditText>(R.id.pickerInput).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!pickerTyping && pickerLayer.isShown && !pickerRemove) { pickerPage = 0; renderPickerNames() }
            }
        })
        findViewById<EditText>(R.id.pickerInput).setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (cleanName(v.text.toString()) != null) addNewPlayer(v.text.toString()) else hideKeyboard()
                true
            } else false
        }

        setUpTouch()
        applyCountry()
        updateQr()
        QueueStore.restore(prefs, queue)   // songs survive an accidental exit
        queue.sungTonight = ::sungTonight
        queue.fair = prefs.getBoolean("fair", false)
        updateFairButton()
        goLobby()
        lobbyBrowser.runQuickPick(0)
        if (savedInstanceState == null) playIntro()
    }

    // =====================================================================================
    // Opening animation (about 3 seconds; any key or tap skips it)
    // =====================================================================================

    private var intro: View? = null

    private fun playIntro() {
        val v = findViewById<View>(R.id.introLayer) ?: return
        // Animations switched off in the system settings (accessibility): straight to the party
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) { (v.parent as? ViewGroup)?.removeView(v); return }
        intro = v
        v.visibility = View.VISIBLE
        v.setOnClickListener { finishIntro(fast = true) }
        lobby.animate().cancel()
        lobby.alpha = 0f
        val mic = findViewById<View>(R.id.introMic)
        val karaoke = findViewById<View>(R.id.introKaraoke)
        val night = findViewById<View>(R.id.introNight)
        val tag = findViewById<View>(R.id.introTag)
        // 1) the mic drops in and bounces
        mic.alpha = 0f; mic.scaleX = 0.2f; mic.scaleY = 0.2f; mic.rotation = -25f
        mic.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(150).setDuration(550)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
        // 2) KARAOKE slides in from the left, NIGHT from the right
        karaoke.alpha = 0f; karaoke.translationX = -px(260).toFloat()
        karaoke.animate().alpha(1f).translationX(0f).setStartDelay(450).setDuration(500).setInterpolator(DecelerateInterpolator(2f)).start()
        night.alpha = 0f; night.translationX = px(260).toFloat()
        night.animate().alpha(1f).translationX(0f).setStartDelay(650).setDuration(500).setInterpolator(DecelerateInterpolator(2f)).start()
        // 3) NIGHT flickers like a neon sign warming up, then the tagline and a burst of confetti
        tag.alpha = 0f; tag.translationY = px(14).toFloat()
        tag.animate().alpha(1f).translationY(0f).setStartDelay(1350).setDuration(400).start()
        ui.postDelayed(introPop, 350)
        ui.postDelayed(introFlicker, 1200)
        ui.postDelayed(introEnd, 3000)
    }

    private val introPop = Runnable { sfx.play(sfx.pop, 0.6f) }

    private val introFlicker = Runnable {
        val night = findViewById<View>(R.id.introNight) ?: return@Runnable
        ValueAnimator.ofFloat(1f, 0.25f, 1f, 0.5f, 1f).apply {
            duration = 450
            addUpdateListener { night.alpha = it.animatedValue as Float }
            start()
        }
        confetti.burst(90)
        sfx.play(sfx.reveal, 0.35f)
    }

    private val introEnd = Runnable { finishIntro(fast = false) }

    /**
     * The opening's words float up and fade (each one on its own: small and cheap, unlike fading
     * the whole screen) while the lobby slides into place; the intro's views are then thrown away.
     */
    private fun finishIntro(fast: Boolean) {
        val v = intro ?: return
        intro = null
        listOf(introPop, introFlicker, introEnd).forEach { ui.removeCallbacks(it) }
        val ms = if (fast) 200L else 450L
        listOf(R.id.introMic, R.id.introKaraoke, R.id.introNight, R.id.introTag).forEach {
            v.findViewById<View>(it)?.apply { animate().cancel(); animate().alpha(0f).translationY(-px(30).toFloat()).setStartDelay(0).setDuration(ms).start() }
        }
        ui.postDelayed({ (v.parent as? ViewGroup)?.removeView(v) }, ms + 50)
        lobby.animate().cancel()
        lobby.alpha = 1f
        lobby.translationY = px(24).toFloat()
        lobby.animate().translationY(0f).setStartDelay(0).setDuration(ms).setInterpolator(DecelerateInterpolator(2f)).start()
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
            // Android 15+ already draws edge to edge for this app; older versions need asking
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                w.setDecorFitsSystemWindows(false)
            }
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
        net.start()
        startServer()
        onNetworkChanged()
        if (mode == Mode.LOBBY || mode == Mode.INTERMISSION) music.start()
        stageBg.animating = mode != Mode.SINGING
        lastInteraction = System.currentTimeMillis()      // back in the app: a full wait before it starts by itself
        ui.postDelayed(autopilot, 5000)
        // resume whatever was counting down when we left
        if (mode == Mode.INTERMISSION) { ui.removeCallbacks(countdownTick); ui.post(countdownTick) }
        if (mode == Mode.SCORE && scoreDone) { ui.removeCallbacks(scoreAutoNext); ui.postDelayed(scoreAutoNext, 1000) }
        if (mode == Mode.SCORE) { ui.removeCallbacks(scoreMusic); ui.postDelayed(scoreMusic, 1500) }
        if (mode == Mode.SINGING && finale) { ui.removeCallbacks(finaleDone); ui.postDelayed(finaleDone, 1500) }
        if (recapLayer.isShown && recapStep >= 0) recapGo(recapStep)   // replay the moment we left
    }

    override fun onStop() {
        stopListening()
        cheers.save()
        achievements.flush()
        ui.removeCallbacks(recapAuto)
        sfx.stop(sfx.drumroll)
        QueueStore.save(prefs, queue)
        ui.removeCallbacks(autopilot)
        autoStartAt = 0L
        autoStartView.visibility = View.GONE
        ui.removeCallbacks(countdownTick)
        ui.removeCallbacks(scoreAutoNext)
        ui.removeCallbacks(scoreMusic)
        ui.removeCallbacks(finaleDone)
        stopServer()
        net.stop()
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
        recapCount?.cancel()
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
        // Memory is getting tight: 10-15 are "running low/critical" (sent by Android 13 and older),
        // BACKGROUND and above are what newer versions send
        if (level in 10..15 || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
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
        // A tap anywhere during "Starting in 10…" cancels it (and does nothing else)
        if (autoStartView.isShown) { if (ev.action == android.view.MotionEvent.ACTION_UP) cancelAutoStart(); return true }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInteraction = System.currentTimeMillis()
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        // Any button during "Starting in 10…" cancels it (and does nothing else)
        if (autoStartView.isShown) { if (first) cancelAutoStart(); return true }
        // Any button during the opening animation skips it (and does nothing else)
        if (intro != null) { if (first) finishIntro(fast = true); return true }
        val code = e.keyCode
        val back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

        // The night recap covers everything: OK / ▶ next, ◀ back one, BACK leaves
        if (recapLayer.isShown) {
            if (back) { if (first) hideRecap(); return true }
            if (findViewById<View>(R.id.recapEnd).isShown) return super.dispatchKeyEvent(e)
            if (first) when {
                ok || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_NEXT -> recapGo(recapStep + 1)
                code == KeyEvent.KEYCODE_DPAD_LEFT && recapStep > 0 -> recapGo(recapStep - 1)
            }
            return true
        }
        // Overlays first, topmost first (a confirmation sheet can sit on top of the picker)
        if (sheetLayer.isShown) { if (back) { if (first) cancelSheet(); return true }; return super.dispatchKeyEvent(e) }
        if (pickerLayer.isShown) { if (back) { if (first) pickerBack(); return true }; return super.dispatchKeyEvent(e) }
        if (achLayer.isShown) {
            if (back) { if (first) hideSinger(); return true }
            // ▲▼ scroll the list; the focus stays on Back
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
                if (e.action == KeyEvent.ACTION_DOWN)
                    findViewById<ScrollView>(R.id.achScroll).smoothScrollBy(0, px(if (code == KeyEvent.KEYCODE_DPAD_DOWN) 120 else -120))
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (boardLayer.isShown) { if (back) { if (first) hideBoard(); return true }; return super.dispatchKeyEvent(e) }
        if (qrLayer.isShown) { if (back) { if (first) hideBigQr(); return true }; return super.dispatchKeyEvent(e) }
        if (countryLayer.isShown) { if (back) { if (first) hideCountries(); return true }; return super.dispatchKeyEvent(e) }
        if (volumeLayer.isShown) { if (back) { if (first) hideSettings(); return true }; return super.dispatchKeyEvent(e) }
        if (aboutLayer.isShown) { if (back) { if (first) hideAbout(); return true }; return super.dispatchKeyEvent(e) }

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
        applyTheme()
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
        lobby.animate().cancel()
        lobby.alpha = 1f
        lobby.translationY = px(24).toFloat()           // slides into place: position only (no full-screen fade)
        lobby.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        stageBg.animating = true
        music.start()
        renderQueues()
        if (!lobbyBrowser.hasResults()) lobbyBrowser.lastQuery?.let { lobbyBrowser.run(it) }
        val start = findViewById<View>(R.id.btnStart)
        if (start.isShown) start.requestFocus() else if (!lobbyBrowser.focusFirst()) findViewById<View>(R.id.lobbySearch).requestFocus()
    }

    /**
     * Starts the party by itself if songs are waiting and nobody has touched the remote for
     * [AUTOSTART_MS], after a "Starting in 10…" warning that any button cancels. The warning always
     * runs in full (even when a phone adds the first song to a TV left alone for an hour). Once
     * cancelled it waits until the queue changes or a song is played.
     */
    private val autopilot = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val ready = mode == Mode.LOBBY && !queue.isEmpty() && !anyOverlayOpen() && !autoStartHeld
            if (ready && autoStartAt == 0L && now - lastInteraction >= AUTOSTART_MS - AUTOSTART_WARN_MS) autoStartAt = now + AUTOSTART_WARN_MS
            val left = autoStartAt - now
            when {
                !ready || autoStartAt == 0L -> { autoStartAt = 0L; autoStartView.visibility = View.GONE }
                left <= 0 -> {
                    autoStartAt = 0L
                    autoStartView.visibility = View.GONE
                    toast("🎤 Songs are waiting. Let's go!")
                    startNext()
                }
                else -> {
                    val next = queue.snapshot().firstOrNull()?.singer.orEmpty()
                    autoStartView.text = "▶ Starting in ${(left + 999) / 1000}…" + (if (next.isNotEmpty()) "  $next is up first" else "") +
                        "\n" + if (isTouch) "Tap anywhere to wait" else "Press any button to wait"
                    if (!autoStartView.isShown) { autoStartView.visibility = View.VISIBLE; popIn(autoStartView) }
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** When "Starting in 10…" reaches 0 (0: no countdown running). */
    private var autoStartAt = 0L

    /** "Starting in 10…" cancelled: no starting by itself until the queue changes or a song plays. */
    private var autoStartHeld = false
    private val autoStartView by lazy { findViewById<TextView>(R.id.autoStart) }

    private fun cancelAutoStart() {
        autoStartHeld = true
        autoStartAt = 0L
        autoStartView.visibility = View.GONE
        toast("OK, waiting. Press ▶ START when you're ready")
    }

    // =====================================================================================
    // Queue display
    // =====================================================================================

    private fun onQueueChanged() {
        autoStartHeld = false                      // new songs: the party may start by itself again
        QueueStore.save(prefs, queue)
        renderQueues()
        if (mode == Mode.SCORE && scoreDone) updateScoreNext()
        if (mode == Mode.INTERMISSION) updateIntermissionInfo()
        if (mode == Mode.SINGING) updateNextPill()
    }

    private fun renderQueues() {
        val items = queue.snapshot()
        renderQueueList(findViewById(R.id.lobbyQueueList), items, 30, fallback = findViewById(R.id.btnStart), guide = true)
        findViewById<TextView>(R.id.lobbyQueueTitle).text =
            (if (items.isEmpty()) "UP NEXT" else "UP NEXT · ${items.size}") + if (queue.fair) "  ·  ⚖️ FAIR" else ""
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
        // Songs that weren't in this list a moment ago slide in (not on the first fill, nor when it opens)
        val before = (list.tag as? Set<*>)?.takeIf { list.isShown }
        list.tag = items.take(max).mapTo(HashSet()) { it.key }
        list.removeAllViews()
        items.take(max).forEachIndexed { i, item ->
            val row = layoutInflater.inflate(R.layout.item_queue, list, false)
            row.findViewById<TextView>(R.id.qAvatar).avatar(item.singer)
            row.findViewById<TextView>(R.id.qSinger).text = "${i + 1}. ${item.singer}"
            row.findViewById<TextView>(R.id.qSong).text = item.song.title
            row.findViewById<TextView>(R.id.qBadge).text = (if (item.fromPhone) "📱" else "") + (if (item.dedication != null) "💌" else "")
            row.setOnClickListener { queueItemMenu(item) }
            list.addView(row)
            if (before != null && item.key !in before) slideIn(row, 60L * i.coerceAtMost(3))
        }
        if (items.size > max) list.addView(smallText("+ ${items.size - max} more waiting"))
        if (items.isEmpty()) list.addView(smallText(
            if (guide) "How it works:\n\n1.  Pick a song\n2.  Choose who sings\n3.  Press ▶ START\n\n📱 Guests can scan the code below to add songs from their phones."
            else "No songs yet. Add one below, or scan the code with your phone!"
        ).apply { textSize = 12f; setLineSpacing(0f, 1.1f) })
        if (hadFocus) {
            val target = list.getChildAt(0)?.takeIf { it.isFocusable } ?: fallback
            if (!target.requestFocus()) lobbyBrowser.focusFirst()
        }
    }

    private fun smallText(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
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

    /** Duet mode in the picker: two players are chosen. */
    private var pickerDuet = false
    /** The players chosen so far, in order (one for solo, two for a duet). */
    private val pickerChosen = ArrayList<String>()
    /** Names made in the pop-up (typed or random) that aren't saved players yet: shown first. */
    private val pickerNew = ArrayList<String>()
    /** Remove mode: tapping a player asks to remove them (and their scores). */
    private var pickerRemove = false
    /** Which page of names is showing (many players), and a guard while the box is cleared. */
    private var pickerPage = 0
    private var pickerTyping = false

    private fun openPicker(song: Song, from: String?) {
        pickerSong = song
        pickerFrom = from
        pickerDuet = false
        pickerRemove = false
        pickerChosen.clear()
        pickerNew.clear()
        overlayReturnFocus = currentFocus
        clearPickerInput()
        findViewById<EditText>(R.id.pickerFor).setText("")
        pickerLayer.visibility = View.VISIBLE
        popIn(pickerLayer)
        renderPicker(focus = R.id.pickerNames)
    }

    private fun clearPickerInput() {
        pickerTyping = true
        findViewById<EditText>(R.id.pickerInput).setText("")
        pickerTyping = false
        pickerPage = 0
    }

    private fun pickerNeeds() = if (pickerDuet) 2 else 1

    /**
     * Everything in the pop-up for the current state. [focus]: what gets the remote's focus
     * afterwards (R.id.pickerNames = the first name, or the box when there are none yet).
     */
    private fun renderPicker(focus: Int = 0) {
        val remove = pickerRemove
        findViewById<TextView>(R.id.pickerTitle).text = if (remove) "🗑 REMOVE PLAYERS" else "🎤 WHO'S SINGING?"
        findViewById<TextView>(R.id.pickerSong).text =
            if (remove) "Tap a player to remove them. Their scores and achievements are deleted too." else pickerSong?.title.orEmpty()
        findViewById<View>(R.id.pickerModeRow).visibility = if (remove) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pickerSolo).isSelected = !pickerDuet
        findViewById<View>(R.id.pickerDuet).isSelected = pickerDuet
        findViewById<TextView>(R.id.pickerSolo).text = if (!pickerDuet) "✓ 🎤 Solo" else "🎤 Solo"
        findViewById<TextView>(R.id.pickerDuet).text = if (pickerDuet) "✓ 👫 Duet" else "👫 Duet"
        findViewById<TextView>(R.id.pickerPlayersLabel).text = when {
            remove -> "PLAYERS"
            pickerDuet -> "PLAYERS  ·  PICK TWO"
            else -> "PLAYERS  ·  PICK ONE"
        }
        findViewById<View>(R.id.pickerRandom).visibility = if (remove) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pickerInput).visibility = if (remove) View.INVISIBLE else View.VISIBLE
        findViewById<TextView>(R.id.pickerRemove).apply {
            text = if (remove) "✓ Done" else "🗑 Remove"
            visibility = if (remove || board.players().isNotEmpty()) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.pickerCancel).visibility = if (remove) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pickerGo).visibility = if (remove) View.GONE else View.VISIBLE
        renderPickerSummary()
        renderPickerNames()
        lockFocus()
        when (focus) {
            0 -> Unit
            R.id.pickerNames -> {
                val firstName = (findViewById<LinearLayout>(R.id.pickerNames).getChildAt(0) as? ViewGroup)?.getChildAt(0)
                (firstName ?: findViewById<View>(if (remove) R.id.pickerRemove else R.id.pickerInput)).requestFocus()
            }
            else -> findViewById<View>(focus)?.takeIf { it.isShown }?.requestFocus()
        }
    }

    /** "🎤 Ana" / "👫 Ana & …" and the button: Sing now! when nobody is waiting, Add otherwise. */
    private fun renderPickerSummary() {
        val chosen = pickerChosen
        findViewById<TextView>(R.id.pickerSummary).apply {
            text = when {
                pickerRemove -> ""
                chosen.isEmpty() -> if (pickerDuet) "Pick two players" else "Pick a player"
                pickerDuet && chosen.size == 1 -> "👫 ${chosen[0]} & …"
                pickerDuet -> "👫 ${chosen.joinToString(DUET_JOIN)}"
                else -> "🎤 ${chosen[0]}"
            }
            setTextColor(getColor(if (chosen.size >= pickerNeeds()) R.color.text_main else R.color.text_dim))
        }
        val ready = pickerChosen.size >= pickerNeeds()
        findViewById<TextView>(R.id.pickerGo).apply {
            text = if (mode == Mode.LOBBY && queue.isEmpty()) "🎤 Sing now!" else "＋ Add to queue"
            alpha = if (ready) 1f else 0.55f
        }
    }

    /**
     * The name buttons, eight at a time: new names first, then whoever is in the queue, then saved
     * players. Typing in the box filters them (names starting with the text first).
     */
    private fun renderPickerNames() {
        val typed = findViewById<EditText>(R.id.pickerInput).text.toString().trim().lowercase()
        val all = (if (pickerRemove) board.players()
        else pickerNew + queue.snapshot().flatMap { it.singer.split(DUET_JOIN) } +
            (queue.current?.singer?.split(DUET_JOIN) ?: emptyList()) + board.players())
            .distinctBy { it.lowercase() }
        val matching = if (typed.isEmpty() || pickerRemove) all
        else all.filter { it.lowercase().startsWith(typed) } + all.filter { it.lowercase().let { n -> !n.startsWith(typed) && n.contains(typed) } }
        val perPage = 8
        val pages = maxOf(1, (matching.size + perPage - 1) / perPage)
        pickerPage = pickerPage.coerceIn(0, pages - 1)
        val names = matching.drop(pickerPage * perPage).take(perPage)

        val box = findViewById<LinearLayout>(R.id.pickerNames)
        box.removeAllViews()
        names.chunked(4).forEach { rowNames ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rowNames.forEach { name ->
                val chosen = pickerChosen.any { it.equals(name, ignoreCase = true) }
                row.addView(styledButton(if (pickerRemove) "✕ $name" else if (chosen) "✓ $name" else name, false).apply {
                    minWidth = px(140)
                    maxWidth = px(152)   // long names get "…" instead of pushing the row off screen
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isSelected = chosen
                    contentDescription = if (pickerRemove) "Remove $name" else if (chosen) "$name, chosen" else name
                    setOnClickListener { if (pickerRemove) askRemovePlayer(name) else togglePlayer(name) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(46)).apply { setMargins(px(5), px(4), px(5), px(4)) })
            }
            box.addView(row)
        }
        if (names.isEmpty()) box.addView(label(when {
            pickerRemove -> "No saved players."
            typed.isNotEmpty() -> "New player: press ${if (isTouch) "Done" else "OK"} to add “${typed.take(20)}”"
            else -> "No players yet. Type a name below, or try 🎲 Random!"
        }, 15f, R.color.text_dim).apply { gravity = Gravity.CENTER })
        findViewById<View>(R.id.pickerPager).visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        findViewById<TextView>(R.id.pickerPageText).text = "${pickerPage + 1} / $pages"
        findViewById<View>(R.id.pickerPrev).apply { isEnabled = pickerPage > 0; alpha = if (isEnabled) 1f else 0.35f }
        findViewById<View>(R.id.pickerNext).apply { isEnabled = pickerPage < pages - 1; alpha = if (isEnabled) 1f else 0.35f }
    }

    /**
     * Picks (or un-picks) a player. Solo: the new pick replaces the old one. Duet: up to two; a
     * third pick replaces the second. Once everyone is chosen, the remote's focus jumps to the
     * Sing now / Add button, so a solo singer is still just two presses away.
     */
    private fun togglePlayer(name: String) {
        val i = pickerChosen.indexOfFirst { it.equals(name, ignoreCase = true) }
        when {
            i >= 0 -> pickerChosen.removeAt(i)
            !pickerDuet -> { pickerChosen.clear(); pickerChosen += name }
            pickerChosen.size >= 2 -> pickerChosen[1] = name
            else -> pickerChosen += name
        }
        sfx.play(sfx.pop, 0.4f)
        val focusedName = (currentFocus as? TextView)?.takeIf { it.parent?.parent === findViewById<View>(R.id.pickerNames) }
        val ready = pickerChosen.size >= pickerNeeds()
        renderPicker()
        if (ready && i < 0) findViewById<View>(R.id.pickerGo).requestFocus()
        else if (focusedName != null) refocusName(name)
    }

    /** After the names are redrawn, puts the focus back on [name]'s button (or the first one). */
    private fun refocusName(name: String) {
        val box = findViewById<LinearLayout>(R.id.pickerNames)
        for (r in 0 until box.childCount) {
            val row = box.getChildAt(r) as? ViewGroup ?: continue
            for (c in 0 until row.childCount) {
                val b = row.getChildAt(c) as? TextView ?: continue
                if (b.text.toString().removePrefix("✓ ").removePrefix("✕ ").equals(name, ignoreCase = true)) { b.requestFocus(); return }
            }
        }
        ((box.getChildAt(0) as? ViewGroup)?.getChildAt(0) ?: findViewById(R.id.pickerInput)).requestFocus()
    }

    private fun setPickerMode(duet: Boolean) {
        if (pickerDuet == duet) return
        pickerDuet = duet
        if (!duet && pickerChosen.size > 1) pickerChosen.subList(1, pickerChosen.size).clear()
        sfx.play(sfx.pop, 0.4f)
        renderPicker(focus = if (duet) R.id.pickerDuet else R.id.pickerSolo)
    }

    /** A new player (typed, or a random stage name): shown first and chosen straight away. */
    private fun addNewPlayer(raw: String) {
        val name = cleanName(raw) ?: return
        val existing = (board.players() + pickerNew).firstOrNull { it.equals(name, ignoreCase = true) }
        val n = existing ?: name.also { pickerNew.add(0, it) }
        clearPickerInput()
        hideKeyboard()
        if (pickerChosen.none { it.equals(n, ignoreCase = true) }) togglePlayer(n) else renderPicker(focus = R.id.pickerGo)
    }

    /** The bottom button: starts (or queues) the song once enough players are chosen. */
    private fun pickerGo() {
        // A name typed but not added yet counts as picked
        val typed = findViewById<EditText>(R.id.pickerInput).text.toString()
        if (pickerChosen.size < pickerNeeds() && cleanName(typed) != null) {
            addNewPlayer(typed)
            if (pickerChosen.size < pickerNeeds()) return
        }
        if (pickerChosen.size < pickerNeeds()) {
            toast(if (pickerDuet) "Pick two players for the duet 👫" else "Pick who's singing first 🎤")
            renderPicker(focus = R.id.pickerNames)
            return
        }
        startPicked(pickerChosen.toList())
    }

    /** BACK in the picker steps back: out of remove mode, then close. */
    private fun pickerBack() {
        if (pickerRemove) { pickerRemove = false; renderPicker(focus = R.id.pickerRemove) } else closePicker()
    }

    /** Second confirmation before a player and their scores are removed. "Keep" is the default. */
    private fun askRemovePlayer(name: String) {
        val back = overlayReturnFocus            // where focus goes when the picker closes
        showSheet(
            "Remove $name?",
            "$name and all their scores (solo and duets) will be deleted from the hall of fame. This can't be undone.",
            listOf(
                "Keep $name" to { overlayReturnFocus = back; renderPicker(focus = R.id.pickerNames) },
                "🗑 Remove" to {
                    overlayReturnFocus = back
                    if (board.removePlayer(name)) {
                        achievements.remove(name)
                        history.forgetSinger(name)
                        pickerChosen.removeAll { it.equals(name, ignoreCase = true) }
                        toast("🗑 $name removed")
                    }
                    if (board.players().isEmpty()) pickerRemove = false
                    renderPicker(focus = R.id.pickerNames)
                },
            ),
            onCancel = { overlayReturnFocus = back; renderPicker(focus = R.id.pickerNames) },
        )
    }

    private fun randomStageName(): String {
        val taken = (queue.snapshot().map { it.singer } + listOfNotNull(queue.current?.singer) + pickerNew).map { it.lowercase() }.toSet()
        return STAGE_NAMES.shuffled().firstOrNull { it.lowercase() !in taken } ?: "Mystery Singer ${Random.nextInt(2, 99)}"
    }

    private fun startPicked(names: List<String>) {
        val song = pickerSong ?: return
        val duet = names.size == 2
        val singer = names.joinToString(DUET_JOIN)
        val singNow = mode == Mode.LOBBY && queue.isEmpty()
        val forWho = dedicationFor(findViewById<EditText>(R.id.pickerFor).text.toString(), singer)
        val item = QueueItem(song, singer, fromPhone = false, duet = duet, genres = Genres.detect(song.title, pickerFrom), dedication = forWho)
        val err = queue.add(item)
        closePicker()
        if (err != null) { toast(err); return }
        board.rememberPlayers(names)
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
        hideDedication()
        dedicationShown = 0L
        autoStartHeld = false
        autoStartView.visibility = View.GONE
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
            val v = findViewById<TextView>(R.id.imCount)
            // No internet: hold here (the banner says why) and carry on when it's back
            if (!net.online || waitingForNet) {
                v.text = "📡"
                if (net.online) { waitingForNet = false; countdown = 3 } else { ui.postDelayed(this, 1000); return }
            }
            if (countdown <= 0) { beginSinging(); return }
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

    // ---------- fair queue ----------

    private fun toggleFair() {
        queue.fair = !queue.fair
        prefs.edit().putBoolean("fair", queue.fair).apply()
        updateFairButton()
    }

    private fun updateFairButton() {
        findViewById<TextView>(R.id.settingsFair).apply {
            text = if (queue.fair) "⚖️ Fair queue: On" else "⚖️ Fair queue: Off"
            isSelected = queue.fair
        }
    }

    /**
     * Songs each person has sung tonight (lower-case names), for the fair queue: the songs played
     * tonight, plus the one being sung now. Called from the phones' threads too: it only reads.
     */
    private fun sungTonight(): Map<String, Int> {
        val since = System.currentTimeMillis() - Leaderboard.TONIGHT_MS
        val played = history.all().filter { it.time >= since }
        val counts = HashMap<String, Int>()
        fun count(singer: String) = singer.split(DUET_JOIN).forEach { p -> if (p.isNotBlank()) counts.merge(p.lowercase(), 1, Int::plus) }
        played.forEach { count(it.singer) }
        queue.current?.let { now -> if (played.none { it.song.id == now.song.id }) count(now.singer) }
        return counts
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

    // ---------- dedications ----------

    /** The song whose dedication has been shown (it's shown once, then the song starts). */
    private var dedicationShown = 0L
    private val dedicationLayer by lazy { findViewById<View>(R.id.dedicationLayer) }

    /** "Ana sings this for Bia" for [DEDICATION_MS], then the song starts (OK starts it straight away). */
    private fun showDedication(item: QueueItem, forWho: String) {
        dedicationShown = item.key
        findViewById<TextView>(R.id.dedText).text = "${item.singer} ${if (item.duet) "sing" else "sings"} this for $forWho"
        findViewById<TextView>(R.id.dedSong).text = shortTitle(item.song.title)
        dedicationLayer.visibility = View.VISIBLE
        popIn(dedicationLayer)
        // The 💌 flies in with a wobble, then the words rise under it
        findViewById<View>(R.id.dedIcon).apply {
            animate().cancel()
            scaleX = 0f; scaleY = 0f; rotation = -35f
            animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(120).setDuration(520)
                .setInterpolator(OvershootInterpolator(3f)).start()
        }
        findViewById<View>(R.id.dedText).apply {
            animate().cancel()
            alpha = 0f; translationY = px(24).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(320).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        sfx.play(sfx.reveal, 0.7f)
        confetti.burst(90)
        ui.removeCallbacks(dedicationDone)
        ui.postDelayed(dedicationDone, DEDICATION_MS)
    }

    private val dedicationDone = Runnable { if (mode == Mode.INTERMISSION) beginSinging() }

    private fun hideDedication() {
        ui.removeCallbacks(dedicationDone)
        dedicationLayer.visibility = View.GONE
    }

    private fun beginSinging() {
        val item = queue.current ?: return goLobby()
        phoneOwners.note(item)                     // also covers songs queued before the app restarted
        if (repairingKey == item.key) return   // still looking for a version that plays
        ui.removeCallbacks(countdownTick)
        // A dedication first (once): "Ana sings this for Bia"
        val forWho = item.dedication
        if (forWho != null && dedicationShown != item.key) { showDedication(item, forWho); return }
        hideDedication()
        if (!net.online) { ui.post(countdownTick); return }   // holds on "Next up" until the internet is back
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
        playerState = PlayerConstants.PlayerState.UNSTARTED
        votes.clear()
        songEmojis.clear()
        resetHype()
        p.loadVideo(item.song.id, 0f)
        startTries = 0
        slowChecks = 0
        ui.removeCallbacks(startWatch)
        ui.postDelayed(startWatch, START_CHECK_MS)
    }

    /** The player's last reported state (UNSTARTED until the video reports in). */
    private var playerState = PlayerConstants.PlayerState.UNKNOWN
    private var startTries = 0
    private var slowChecks = 0

    /**
     * Some videos load but never start by themselves: YouTube waits for a tap (an ad in front,
     * or autoplay refused for that video). If nothing plays after a few seconds, nudge it: play
     * again, then a tap on the player, then load it once more; if it still won't start, find
     * another version of the song, as for a blocked video. A slow connection (still buffering)
     * gets more time before anything drastic.
     */
    private val startWatch: Runnable = object : Runnable {
        override fun run() {
            if (mode != Mode.SINGING || finale) return
            val p = player ?: return
            val item = queue.current ?: return
            // No internet: nothing to blame the video for; it carries on when the internet is back
            if (!net.online) { ui.postDelayed(this, START_CHECK_MS); return }
            val st = playerState
            // Started already, or paused by the singer (the "Done singing?" menu): nothing to do
            if (st == PlayerConstants.PlayerState.PLAYING || st == PlayerConstants.PlayerState.ENDED || maxSecond >= 1f) return
            if (st == PlayerConstants.PlayerState.PAUSED && sheetLayer.isShown) return
            if (st == PlayerConstants.PlayerState.BUFFERING && slowChecks < 6) {
                slowChecks++                      // a slow connection: keep asking, give it time
                p.play()
            } else when (++startTries) {
                1 -> p.play()
                2 -> { tapPlayer(); p.play() }
                3 -> { p.loadVideo(item.song.id, 0f); net.suspect() }   // and is the internet there?
                4 -> tapPlayer()
                else -> {
                    tryAnotherVersion(item, "🔁 That video won't start. Finding another version…")
                    return
                }
            }
            ui.postDelayed(this, START_CHECK_MS)
        }
    }

    private val stallCheck = Runnable {
        if (mode == Mode.SINGING && playerState == PlayerConstants.PlayerState.BUFFERING) net.suspect()
    }

    /** A tap in the middle of the video: for YouTube, the "user gesture" some videos wait for. */
    private fun tapPlayer() {
        val web = playerView?.let { findWebView(it) } ?: return
        if (web.width <= 0 || web.height <= 0) return
        val t = android.os.SystemClock.uptimeMillis()
        val x = web.width / 2f
        val y = web.height / 2f
        runCatching {
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
            MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0).also { web.dispatchTouchEvent(it); it.recycle() }
        }
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
        updateLive()
        ui.removeCallbacks(finaleDone)
        ui.postDelayed(finaleDone, FINALE_MS)
    }

    private val finaleDone = Runnable { if (mode == Mode.SINGING && finale) showScore(1.0) }

    /** The lobby music comes back gently a few seconds after the score has landed. */
    private val scoreMusic = Runnable { if (mode == Mode.SCORE) music.start(fadeMs = 3000) }

    /** Shows or hides the video area (and the tap catcher above it). */
    private fun stage(on: Boolean) {
        stageContainer.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (!on) { ui.removeCallbacks(startWatch); ui.removeCallbacks(stallCheck) }
        stageTouch.visibility = if (on && isTouch) View.VISIBLE else View.GONE
        finale = false
        ui.removeCallbacks(finaleDone)
        isPaused = false
        if (!on) stageCurtain.visibility = View.GONE
        // Guests' cheers only exist over the video; hiding the view frees its emoji pictures
        if (!on) stageReactions.clear()
        stageReactions.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { ui.removeCallbacks(hypeTick); hypeBar.visibility = View.GONE; hypeLabel.visibility = View.GONE }
        updateLive()
    }

    // ---- party themes (special dates) ----

    /** Christmas, Halloween or Carnival tonight? Then the stage and confetti dress up (with a hello). */
    private fun applyTheme() {
        val t = PartyTheme.forNight(Achievements.nightOf(System.currentTimeMillis()))
        if (t == theme) return
        theme = t
        stageBg.theme = t
        confetti.colors = t.confetti
        t.greeting?.let { ui.postDelayed({ if (mode == Mode.LOBBY) toast(it) }, THEME_HELLO_MS) }
    }

    // ---- hype meter ----

    private fun resetHype() {
        hype.reset()
        ui.removeCallbacks(hypeTick)
        hypeBar.apply { level = 0f; visibility = View.GONE; animate().cancel(); scaleX = 1f; alpha = 0.85f }
        hypeLabel.animate().cancel(); hypeLabel.visibility = View.GONE
    }

    /** A cheer (or 😴 / 🍅) from a phone: the bar moves a little, and slowly drifts back once the room goes quiet. */
    private fun hypeCheer(positive: Boolean) {
        val ended = hype.cheer(System.currentTimeMillis(), positive, queue.current?.let { roomFor(it) } ?: 0)
        showHype()
        if (ended) {
            // An end reached: a short pulse and one emoji; the points wait for the score
            hypeBar.animate().cancel()
            hypeBar.scaleX = 2.4f; hypeBar.alpha = 1f
            hypeBar.animate().scaleX(1f).setDuration(700).setInterpolator(OvershootInterpolator(2f)).start()
            stageReactions.cheer(if (hype.full) "🔥" else "🍅")
            if (hype.full) hypeLabel.apply {
                // Now it's revealed: "🔥 HYPE" pops in beside the bar and stays for the rest of the song
                visibility = View.VISIBLE; alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(500).start()
            }
        }
    }

    private fun showHype() {
        val now = System.currentTimeMillis()
        if (!liveNow()) hype.freeze(now)                  // paused: the bar waits too
        val level = hype.at(now)
        hypeBar.level = level.toFloat()
        hypeBar.visibility = if (mode == Mode.SINGING && level != 0.0) View.VISIBLE else View.GONE
        ui.removeCallbacks(hypeTick)
        if (hypeBar.isShown && hype.points == 0) ui.postDelayed(hypeTick, HYPE_TICK_MS)
    }

    private val hypeTick = Runnable { showHype() }

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
        // Straight on and off: a full-screen fade over a playing video is costly on the Shield
        if (show && stageCurtain.visibility != View.VISIBLE) stageCurtain.visibility = View.VISIBLE
        else if (!show && stageCurtain.visibility == View.VISIBLE) stageCurtain.visibility = View.GONE
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
        hideDedication()
        dedicationShown = 0L
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
     * The songs' volume, 0-100 (sound effects and the lobby music have their own sliders, see
     * [fxVolume] and [LobbyMusic.volume]). Separate from the TV's own volume and the microphones.
     */
    private fun setVolumeTo(value: Int, save: Boolean = true) {
        volume = value.coerceIn(0, 100)
        if (save) prefs.edit().putInt("music_volume", volume).putInt("fx_volume", fxVolume).apply()
        player?.setVolume(volume)
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
        opts += "⏭ Skip" to { skipSong(f) }
        opts += "🏠 Lobby" to { goLobby() }
        showSheet(
            "Done singing?",
            if (f >= 0.25) "You're ${(f * 100).toInt()}% through. Stopping early costs points!"
            else "Sing at least a quarter of the song to get a score.",
            opts,
            onCancel = { player?.play() }
        )
    }

    /**
     * Skipped from the menu: no score, but emoji achievements still count, and skipping right at
     * the end (the last 10%, or during the "Big finish") earns 🤨 Huh?!
     */
    private fun skipSong(fraction: Double) {
        val item = queue.current
        if (item != null) {
            val extra = if (finale || fraction >= HUH_FRACTION) setOf("huh") else emptySet()
            val got = runCatching { achievements.unlockNow(item.singer, extra, skipped = true) }.getOrDefault(emptyList())
            if (got.isNotEmpty()) toast(got.take(2).joinToString("  ·  ") { (who, a) -> "🏅 $who unlocked ${a.icon} ${a.title}" })
        }
        afterSong()
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
        if (mode == Mode.SCORE) return            // already showing (end of video and a tap at once)
        ui.removeCallbacks(finaleDone)
        val item = queue.current ?: return goLobby()
        history.add(item.song, item.singer)   // for "🕘 Played", even when stopped early
        cheers.save()
        // The room decides most of it: the phones' votes pull the random roll towards what they
        // thought (more so when more of the room voted), then the hype bar and past crowds count too
        songRep = runCatching { achievements.reputation(item.singer) }.getOrDefault(0.0)
        val phoneVotes = votes.values.map { it[0].toDouble() / it[1] }     // each phone: the average of its emojis
        val crowd = Judge.crowd(phoneVotes, roomFor(item))
        val score = Judge.score(fraction, crowd, bonus = hype.points, reputation = songRep) ?: return afterSong()
        songRoom = crowd.average * crowd.weight             // breaks ties in the rankings
        if (phoneVotes.size >= Judge.REP_VOTERS) runCatching { achievements.rated(item.singer, phoneVotes.average()) }
        votes.clear()
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
        // Invisible, not gone: their space is kept, so nothing shifts as they appear
        listOf(R.id.scStars, R.id.scVerdict, R.id.scRank, R.id.scBadge, R.id.scNext).forEach {
            findViewById<View>(it).visibility = View.INVISIBLE
        }
        showJudgeColumns(false)
        scoreLayer.visibility = View.VISIBLE
        popIn(scoreLayer)

        // 1) drumroll with shuffling numbers
        sfx.play(sfx.drumroll)
        val started = System.currentTimeMillis()
        val shuffle = object : Runnable {
            override fun run() {
                if (mode != Mode.SCORE) return
                if (System.currentTimeMillis() - started >= 2600) { countUp(score, item, fraction < Judge.FINISHED); return }
                scoreView.text = scoreText(Random.nextInt(100, 1000) / 10.0)
                scoreView.alpha = 0.5f + Random.nextFloat() * 0.5f
                ui.postDelayed(this, 70)
            }
        }
        ui.postDelayed(shuffle, 250)
    }

    private fun countUp(target: Double, item: QueueItem, stoppedEarly: Boolean) {
        sfx.stop(sfx.drumroll)
        val scoreView = findViewById<TextView>(R.id.scScore)
        scoreView.alpha = 1f
        var last = -1
        scoreAnim = ValueAnimator.ofFloat(0f, target.toFloat()).apply {
            duration = 1500
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                val v = (it.animatedValue as Float).toDouble()
                scoreView.text = bigScore(Judge.tenth(v).coerceAtMost(target))
                val whole = v.toInt()
                if (whole != last) {
                    last = whole
                    if (whole % 3 == 0) sfx.play(sfx.tick, 0.35f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled && mode == Mode.SCORE) land(target, item, stoppedEarly)
                }
            })
            start()
        }
    }

    private fun land(score: Double, item: QueueItem, stoppedEarly: Boolean) {
        val scoreView = findViewById<TextView>(R.id.scScore)
        // The hype bar at an end: a small 🔥 (or 🍅) next to the number
        scoreView.text = bigScore(score, if (hype.points == 0) "" else if (hype.full) "🔥" else "🍅")
        ui.removeCallbacks(scoreMusic)
        ui.postDelayed(scoreMusic, SCORE_MUSIC_DELAY_MS)
        sfx.play(sfx.reveal)
        if (score >= 80) sfx.play(sfx.applause, if (score >= 90) 0.9f else 0.6f)
        scoreView.scaleX = 1.35f; scoreView.scaleY = 1.35f
        scoreView.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(450).start()

        val stars = Judge.stars(score)
        val starsView = findViewById<TextView>(R.id.scStars)
        starsView.text = "☆☆☆☆☆"
        for (n in 1..stars) ui.postDelayed({
            if (mode == Mode.SCORE) { starsView.text = "★".repeat(n) + "☆".repeat(5 - n); sfx.play(sfx.pop, 0.5f) }
        }, 350L + n * 160L)
        findViewById<TextView>(R.id.scVerdict).text = Judge.verdict(score)

        val entry = Entry(item.singer, score, item.song.title, System.currentTimeMillis(), duet = item.duet, room = songRoom)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val before = board.all()                  // a snapshot: add() replaces the list, never changes it
        val record = if (item.duet) null else achievements.of(item.singer)
        val badges = Badges.earned(entry, board.tonightAll(), record?.songs ?: 0, record?.best, hour, limit = 9)
        val rank = board.add(entry)
        val unlocked = runCatching { achievements.record(entry, before, rank, hour, item.genres, stoppedEarly) }.getOrDefault(emptyList())
        val hypeLine = when {
            hype.full -> listOf("🔥 The crowd went wild: +${Hype.BONUS}")
            hype.booed -> listOf("🍅 The crowd wasn't feeling it: -${Hype.BONUS}")
            else -> emptyList()
        }
        val repLine = when {
            songRep >= 0.5 -> listOf("⭐ A crowd favourite: +1")
            songRep <= -0.5 -> listOf("📉 Past crowds were lukewarm: -1")
            else -> emptyList()
        }
        val lines = scoreBadgeLines(unlocked, hypeLine + badges + repLine)   // big news first
        findViewById<TextView>(R.id.scBadge).apply {
            text = lines.joinToString("\n")
            if (lines.isNotEmpty()) {
                if (unlocked.isNotEmpty()) ui.postDelayed({ if (mode == Mode.SCORE) sfx.play(sfx.pop, 0.9f) }, 900)
                visibility = View.VISIBLE
                alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(900).setInterpolator(OvershootInterpolator(2.5f)).setDuration(420).start()
            }
        }
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

        // Once in a while, for a great score: the judges, the emoji crowd, or both (a surprise which)
        val extras = Extras.pick(score, songEmojis.values.sum())
        if (Extras.Kind.EMOJIS in extras) Extras.emojis(score, songEmojis).let { (set, rate) -> findViewById<ReactionsView>(R.id.scReactions).start(set, rate) }
        if (Extras.Kind.JUDGES in extras) showJudges(score)

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
        }, if (Extras.Kind.JUDGES in extras) 2900L else 1800L)   // after the last paddle when the judges are on
    }

    /**
     * The judges' columns, either side of the score. While they're showing, the middle column is
     * kept narrower so nothing runs under them; either way the score stays centred.
     */
    private fun showJudgeColumns(show: Boolean) {
        listOf(R.id.scJudgesLeft, R.id.scJudgesRight).forEach {
            findViewById<LinearLayout>(it).apply { removeAllViews(); visibility = if (show) View.VISIBLE else View.GONE }
        }
        val width = px(if (show) 440 else 700)
        listOf(R.id.scSong, R.id.scBadge).forEach { findViewById<TextView>(it).maxWidth = width }
    }

    /** Four judges hold up their paddles one after another, alternating sides. */
    /** The big score: "87" at full size, ".4" smaller (it's there to break ties, not to shout), then [suffix] (the hype bar's 🔥/🍅). */
    private fun bigScore(score: Double, suffix: String = ""): CharSequence {
        val text = scoreText(score)
        return android.text.SpannableString(text + suffix).apply {
            val dot = text.indexOf('.')
            if (dot >= 0) setSpan(android.text.style.RelativeSizeSpan(0.5f), dot, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (suffix.isNotEmpty()) setSpan(android.text.style.RelativeSizeSpan(0.42f), text.length, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showJudges(score: Double) {
        showJudgeColumns(true)
        val left = findViewById<LinearLayout>(R.id.scJudgesLeft)
        val right = findViewById<LinearLayout>(R.id.scJudgesRight)
        Extras.judges(score).forEachIndexed { i, j ->
            val column = if (i % 2 == 0) left else right
            val card = judgeCard(j, paddleFirst = column === right)
            column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = if (column.childCount == 0) 0 else px(12) })
            card.alpha = 0f
            card.translationX = (if (column === left) -px(40) else px(40)).toFloat()
            val delay = 900L + i * 450L
            card.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(320).start()
            ui.postDelayed({ if (mode == Mode.SCORE) sfx.play(sfx.pop, 0.8f) }, delay)
            card.findViewWithTag<View>("paddle")?.apply {
                scaleX = 0.2f; scaleY = 0.2f; rotation = -20f
                animate().scaleX(1f).scaleY(1f).rotation(if (i % 2 == 0) -4f else 4f)
                    .setStartDelay(delay + 120).setInterpolator(OvershootInterpolator(3f)).setDuration(400).start()
            }
        }
    }

    /** A judge: face, name and quip, and their paddle on the side facing the score. */
    private fun judgeCard(j: JudgeCard, paddleFirst: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.panel_bg)
        setPadding(px(10), px(8), px(10), px(8))
        contentDescription = "${j.name.lowercase().replaceFirstChar { it.uppercase() }}: ${j.paddle} out of 10. ${j.quip}"
        val paddle = TextView(context).apply {
            tag = "paddle"
            text = j.paddle
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xFF0B0614.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // A white scorecard, like on TV talent shows; gold for a 9.5 or 10
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (j.points >= 9.5) getColor(R.color.gold) else 0xFFFFFFFF.toInt())
                cornerRadius = px(8).toFloat()
            }
        }
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), 0, px(8), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(label(j.face + " " + j.name, 11f, R.color.neon_cyan, bold = true).apply { letterSpacing = 0.08f })
            addView(label(j.quip, 13f, R.color.text_main).apply { maxLines = 2 })
        }
        val paddleParams = LinearLayout.LayoutParams(px(52), px(44))
        val wordsParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (paddleFirst) { addView(paddle, paddleParams); addView(words, wordsParams) }
        else { addView(words, wordsParams); addView(paddle, paddleParams) }
    }

    /**
     * The pill under the rank: newly unlocked achievements first ("🏅 NEW: 💎 90 club"), then the
     * moment's highlights (personal best…) that aren't the same thing again. At most two lines.
     */
    private fun scoreBadgeLines(unlocked: List<Pair<String, Achievement>>, badges: List<String>): List<String> {
        val lines = ArrayList<String>()
        if (unlocked.isNotEmpty()) {
            fun list(a: List<Achievement>, max: Int) = a.take(max).joinToString(", ") { "${it.icon} ${it.title}" } +
                if (a.size > max) " +${a.size - max} more" else ""
            val byPerson = unlocked.groupBy({ it.first }, { it.second })
            lines += if (byPerson.size == 1 || byPerson.values.map { l -> l.map { it.id } }.distinct().size == 1)
                "🏅 NEW: " + list(byPerson.values.first(), 3)
            else "🏅 " + byPerson.entries.joinToString("  ·  ") { (who, a) -> "$who: " + list(a, 2) }
        }
        val icons = unlocked.map { it.second.icon }.toSet()
        badges.filter { b -> icons.none { b.startsWith(it) } }.firstOrNull()?.let { lines += it }
        return lines.take(2)
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
            playerState = state
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
                // Stuck loading mid-song for a while: maybe the internet went (the Wi-Fi can stay up)
                PlayerConstants.PlayerState.BUFFERING -> { ui.removeCallbacks(stallCheck); ui.postDelayed(stallCheck, STALL_MS) }
                else -> Unit
            }
            updateCurtain()
            updateLive()
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
            if (!net.online) { waitForInternet(item); return }
            // The video refuses (its owner blocks it) or is gone: remember it, so it (and, after
            // repeats, its channel) stops showing up, and play another version
            if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ||
                error == PlayerConstants.PlayerError.VIDEO_NOT_FOUND
            ) {
                search.markUnplayable(item.song)
                tryAnotherVersion(item)
                return
            }
            // Anything else may be the connection: check before blaming the video
            net.check { online ->
                if (queue.current?.key != item.key || (mode != Mode.INTERMISSION && mode != Mode.SINGING)) return@check
                if (online) tryAnotherVersion(item, "🔁 That video won't play. Finding another version…") else waitForInternet(item)
            }
        }
    }

    /**
     * A video refused to play (usually its owner blocks playback outside YouTube). Instead of
     * skipping the singer, find another karaoke video of the same song and play that.
     */
    private fun tryAnotherVersion(item: QueueItem, message: String = "🔁 That video is blocked by its owner. Finding another version…") {
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
        toast(message)
        val started = runCatching {
            io.execute {
                val alt = runCatching { search.alternativeFor(failed, setOf(failed.id)) }
                ui.post {
                    val e = alt.exceptionOrNull()
                    if (e == null || e is SearchException) onAnotherVersion(item, alt.getOrNull())
                    // The search itself failed: no internet means wait, not skip the singer
                    else net.check { online -> if (online) onAnotherVersion(item, null) else waitForInternet(item) }
                }
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
        val b = PartyBackend(queue, search, board, history, this)
        b.online = net.online
        val s = PartyServer(b, token, page)
        server = s
        backend = b
        updateLive()
        io.execute {
            val port = s.start()
            ui.post {
                if (server === s) {
                    serverPort = port
                    partyToken = token
                    updatePartyUrl()
                }
            }
        }
    }

    /** The phones' link: the TV's current address on the Wi-Fi (it can change), the port and the party key. */
    private fun updatePartyUrl() {
        val ip = net.lanAddress
        val token = partyToken
        val url = if (serverPort > 0 && ip != null && token != null && server != null) "http://$ip:$serverPort/?k=$token" else null
        if (url == partyUrl && findViewById<TextView>(R.id.lobbyUrl).text.isNotEmpty()) return   // QR codes take a moment to draw
        partyUrl = url
        updateQr()
    }

    /** The connection changed: new address for the phones, and the no-internet banner on or off. */
    private fun onNetworkChanged() {
        updatePartyUrl()
        val online = net.online
        backend?.online = online
        findViewById<View>(R.id.netBanner).visibility = if (online) View.GONE else View.VISIBLE
        if (online && wasOffline) {
            toast("✅ Internet is back")
            resumeAfterOutage()
        }
        wasOffline = !online
    }

    /** Back online: whatever was waiting carries on by itself. */
    private fun resumeAfterOutage() {
        lobbyBrowser.retryIfFailed()
        panelBrowser.retryIfFailed()
        val item = queue.current ?: return
        if (mode != Mode.INTERMISSION && mode != Mode.SINGING) return
        // The player page itself may never have loaded: start it again
        if (playerView != null && !playerReady) { releasePlayer(); ensurePlayer() }
        if (mode == Mode.INTERMISSION) {
            if (repairingKey != 0L) return               // already looking for another version
            cue(item.song.id)
            if (waitingForNet) { waitingForNet = false; countdown = 3 }
            ui.removeCallbacks(countdownTick)
            ui.post(countdownTick)
            return
        }
        // Singing: carry on from where the video stopped (unless the singer paused it)
        val st = playerState
        if (finale || st == PlayerConstants.PlayerState.PLAYING || (st == PlayerConstants.PlayerState.PAUSED && sheetLayer.isShown)) return
        player?.loadVideo(item.song.id, maxSecond)
        startTries = 0
        slowChecks = 0
        ui.removeCallbacks(startWatch)
        ui.postDelayed(startWatch, START_CHECK_MS)
    }

    /**
     * No internet for this song: back to "Next up" with the same singer and song. It holds there
     * (📡 instead of the countdown) and starts by itself when the internet is back. Nobody's turn is skipped.
     */
    private fun waitForInternet(item: QueueItem) {
        if (queue.current?.key != item.key) return
        repairingKey = 0L
        if (mode == Mode.SINGING) {
            mode = Mode.INTERMISSION
            listOf(hud, queuePanel).forEach { it.visibility = View.GONE }
            stage(false)
            intermission.visibility = View.VISIBLE
            stageBg.animating = true
            music.start()
        }
        if (mode != Mode.INTERMISSION) return
        waitingForNet = true
        ui.removeCallbacks(countdownTick)
        ui.post(countdownTick)
    }

    /** Synchronous on purpose: closing a socket is instant, and a queued task could be dropped on exit. */
    private fun stopServer() {
        backend?.live = false
        backend = null
        val s = server ?: return
        server = null
        s.stop()
    }

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
        phoneOwners.note(item)
        sfx.play(sfx.pop)
        toast("📱 ${item.singer} added “${item.song.title.take(45)}”")
    }

    /** A phone's search failed for lack of internet. */
    override fun onOffline() = net.suspect()

    override fun onPhoneRemoved(item: QueueItem) {
        toast("📱 ${item.singer} took “${item.song.title.take(45)}” out of the queue")
    }

    /**
     * A guest cheered: a small emoji rises along the edge of the video (no sound: the singer is
     * singing). Secretly it's also that phone's vote: the average of its emojis pulls the score.
     */
    override fun onReaction(index: Int, owner: String) {
        if (!liveNow()) return
        val emoji = REACTIONS.getOrNull(index) ?: return
        stageReactions.cheer(emoji)
        // The singer's own phone only gets to see its emoji rise: no vote, no hype, no cheers or
        // emoji achievements for cheering yourself
        if (queue.current?.let { phoneOwners.isSingers(owner, it) } == true) return
        if (owner in votes || votes.size < MAX_VOTES) votes.getOrPut(owner) { IntArray(2) }.let { it[0] += REACTION_VOTES[index]; it[1]++ }
        if (REACTION_VOTES[index] >= 0) cheers.add(queue.current?.singer, queue.current?.song?.title)   // 😴 and 🍅 aren't cheers
        achievements.cheered(queue.current?.singer, emoji)                   // every emoji counts for the emoji achievements
        songEmojis[emoji] = (songEmojis[emoji] ?: 0) + 1
        hypeCheer(REACTION_VOTES[index] >= 0)
    }

    /** The room for [item]: phones with the party page open, minus the singer's own (it can't vote). 0: unknown. */
    private fun roomFor(item: QueueItem): Int =
        ((server?.phonesNow() ?: 0) - (if (item.owner?.let { phoneOwners.isSingers(it, item) } == true) 1 else 0)).coerceAtLeast(0)

    /** Which phones are someone's own and which are passed around (see [PhoneOwners]). */
    private val phoneOwners = PhoneOwners()

    /** The score's points from the singer's reputation (past crowds), for its badge line. */
    private var songRep = 0.0
    /** How much the room loved this song (average vote times its say), saved with the score for ties. */
    private var songRoom = 0.0

    /** This song's hidden votes: for each phone (fingerprint), the sum of its emojis' votes and how many it sent. */
    private val votes = HashMap<String, IntArray>()

    /** How many of each emoji the phones sent during this song (lots of them: the emoji crowd shows them on the score). */
    private val songEmojis = HashMap<String, Int>()

    /** Phones may cheer while a song is playing (not paused), right up to the "Big finish". */
    private fun liveNow() = mode == Mode.SINGING && (finale || (isPlaying && !isPaused))

    private fun updateLive() { backend?.live = liveNow() }

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
    // About
    // =====================================================================================

    private fun showAbout() {
        overlayReturnFocus = currentFocus
        findViewById<TextView>(R.id.aboutVersion).text = "Version ${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}"
        findViewById<TextView>(R.id.aboutLink).text = PROJECT_URL.removePrefix("https://").trimEnd('/')
        findViewById<QrView>(R.id.aboutQr).setText(PROJECT_URL)
        aboutLayer.visibility = View.VISIBLE
        lockFocus()
        popIn(aboutLayer)
        findViewById<View>(R.id.aboutClose).requestFocus()
    }

    private fun hideAbout() {
        aboutLayer.visibility = View.GONE
        restoreFocus()
    }

    /** Opens the project page in a browser; TVs often have none, so the QR code is the fallback. */
    private fun openProjectPage() {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PROJECT_URL)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No web browser here. Scan the code with your phone 📱")
        } catch (_: SecurityException) {
            toast("No web browser here. Scan the code with your phone 📱")
        }
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

    // ---- settings (the ⚙️ button): volumes, lobby music, fair queue, About ----

    private fun showSettings() {
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
        findViewById<TextView>(R.id.fxValue).text = "$fxVolume%"
        findViewById<SeekBar>(R.id.fxSlider).progress = fxVolume
        val lobby = music.volume
        findViewById<TextView>(R.id.lobbyValue).text = if (music.enabled) "$lobby%" else "Off"
        findViewById<SeekBar>(R.id.lobbySlider).apply { progress = lobby; alpha = if (music.enabled) 1f else 0.5f }
        findViewById<TextView>(R.id.volumeMusic).apply {
            text = if (music.enabled) "🎵 Lobby music: On" else "🔈 Lobby music: Off"
            isSelected = music.enabled
        }
    }

    private fun hideSettings() {
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
                box.hint = "🎙 Listening… say a song"
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

    /** The hall of fame's tabs: four rankings (solo and duets, tonight and all time) and the achievements. */
    private enum class BoardView(val tab: Int, val empty: String) {
        TONIGHT(R.id.tabTonight, "No solo scores tonight yet. Grab a mic! 🎤"),
        ALL(R.id.tabAll, "No solo scores yet. Grab a mic! 🎤"),
        DUETS_TONIGHT(R.id.tabDuetsTonight, "No duets tonight yet. Grab a partner! 👫"),
        DUETS_ALL(R.id.tabDuetsAll, "No duets yet. Grab a partner! 👫"),
        ACHIEVEMENTS(R.id.tabAch, "No achievements yet. Sing a song to earn your first! 🎤"),
    }
    private var boardShows: BoardView? = null

    private fun renderBoard(view: BoardView) {
        if (boardShows == view && boardLayer.isShown && findViewById<LinearLayout>(R.id.boardRows).childCount > 0) return
        boardShows = view
        BoardView.values().forEach { findViewById<View>(it.tab).isSelected = it == view }
        val showAch = view == BoardView.ACHIEVEMENTS
        findViewById<View>(R.id.boardAchScroll).visibility = if (showAch) View.VISIBLE else View.GONE
        findViewById<View>(R.id.boardScroll).visibility = if (showAch) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.boardEmpty).text = view.empty
        if (showAch) { renderAchievementList(); return }
        findViewById<LinearLayout>(R.id.boardAchRows).removeAllViews()
        val entries = when (view) {
            BoardView.TONIGHT -> board.tonight()
            BoardView.ALL -> board.allTime()
            BoardView.DUETS_TONIGHT -> board.tonight(duet = true)
            BoardView.DUETS_ALL -> board.allTime(duet = true)
            BoardView.ACHIEVEMENTS -> emptyList()
        }
        val list = board.top(entries, BOARD_ROWS)
        findViewById<View>(R.id.boardEmpty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.boardScroll).visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        boardHint(if (list.size > 5) "${list.size} scores  ·  ${if (isTouch) "Swipe up" else "Press ▼"} to see them all" else "")

        // Podium: 2nd, 1st, 3rd
        val podium = findViewById<LinearLayout>(R.id.podium)
        podium.removeAllViews()
        listOf(1, 0, 2).forEach { i ->
            val e = list.getOrNull(i) ?: return@forEach
            podium.addView(podiumColumn(e, i), LinearLayout.LayoutParams(px(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // The full list, podium places included, each with the song it was scored with
        val rows = findViewById<LinearLayout>(R.id.boardRows)
        rows.removeAllViews()
        val medals = arrayOf("🥇", "🥈", "🥉")
        list.forEachIndexed { j, e ->
            // Each row takes the focus, so ▼ walks down the list and the list scrolls with it
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(14), px(4), px(14), px(4))
                background = boardRowBg(j % 2 == 0)
                isFocusable = !isTouch
                nextFocusUpId = if (j == 0) view.tab else View.NO_ID
                contentDescription = "${medals.getOrNull(j) ?: "${j + 1}."} ${e.name}, ${scoreText(e.score)} points, ${shortTitle(e.song)}"
            }
            row.id = View.generateViewId()
            row.nextFocusLeftId = row.id; row.nextFocusRightId = row.id
            row.addView(label(medals.getOrNull(j) ?: "${j + 1}.", 15f, R.color.text_dim), LinearLayout.LayoutParams(px(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply { avatar(e.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt()); textSize = 12f },
                LinearLayout.LayoutParams(px(24), px(24)))
            row.addView(label(e.name, 15f, R.color.text_main, bold = true).apply { setPadding(px(10), 0, 0, 0) },
                LinearLayout.LayoutParams(px(if (e.duet) 250 else 170), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(label(shortTitle(e.song), 13f, R.color.text_dim), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(scoreText(e.score), 17f, R.color.gold, bold = true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(60), ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(2) })
        }
        cascade(rows)
    }

    private fun boardHint(text: String) {
        findViewById<TextView>(R.id.boardHint).apply { this.text = text; visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE }
    }

    /** A ranking row: striped, and outlined when the remote is on it. */
    private fun boardRowBg(even: Boolean) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(10).toFloat(); setColor(0xFF3A2466.toInt()); setStroke(px(2), getColor(R.color.neon_pink))
        })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(10).toFloat(); setColor(if (even) 0x26FFFFFF else 0x10FFFFFF)
        })
    }

    // ---- achievements: a list of singers, OK on one opens everything they have (and haven't) ----

    private fun renderAchievementList() {
        val rows = findViewById<LinearLayout>(R.id.boardAchRows)
        rows.removeAllViews()
        val singers = achievements.singers()
        val total = Achievements.MAIN.size
        val genres = Achievements.GENRES.size
        findViewById<TextView>(R.id.boardEmpty).apply {
            text = "No achievements yet. Sing a song to earn your first! 🎤"
            visibility = if (singers.isEmpty()) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.boardAchScroll).visibility = if (singers.isEmpty()) View.GONE else View.VISIBLE
        boardHint(if (singers.size > 6) "${singers.size} singers  ·  ${if (isTouch) "Swipe up" else "Press ▼"} to see them all  ·  ${if (isTouch) "Tap" else "OK"} on a name for their achievements"
            else if (singers.isNotEmpty()) "${if (isTouch) "Tap" else "OK"} on a name for their achievements" else "")
        singers.take(100).forEachIndexed { j, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                isFocusable = true
                isClickable = true
                setPadding(px(14), px(6), px(14), px(6))
                val main = Achievements.MAIN.count { it.id in r.unlocked }
                val sung = Achievements.GENRES.count { it.id in r.unlocked }
                contentDescription = "${r.name}: $main of $total achievements, $sung of $genres genres. Press to see them all."
                setOnClickListener { showSinger(r.name) }
                if (j == 0) nextFocusUpId = R.id.tabAch
            }
            val main = Achievements.MAIN.filter { it.id in r.unlocked }
            val sung = Achievements.GENRES.filter { it.id in r.unlocked }
            row.addView(TextView(this).apply { avatar(r.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt()); textSize = 14f },
                LinearLayout.LayoutParams(px(32), px(32)))
            row.addView(label(r.name, 17f, R.color.text_main, bold = true).apply { setPadding(px(10), 0, px(8), 0) },
                LinearLayout.LayoutParams(px(170), ViewGroup.LayoutParams.WRAP_CONTENT))
            // Their badges in catalogue order, then how many genres they've sung
            row.addView(label(main.joinToString(" ") { it.icon }, 18f, R.color.text_main),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("🎼 ${sung.size}/$genres", 15f, R.color.text_dim).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(76), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(label("${main.size}/$total", 18f, R.color.gold, bold = true).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(px(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48)).apply { bottomMargin = px(6) })
        }
        cascade(rows)
    }

    private fun showSinger(name: String) {
        val r = achievements.of(name) ?: return
        singerReturnFocus = currentFocus           // the hall of fame keeps its own way back
        val total = Achievements.MAIN.size
        val main = Achievements.MAIN.count { it.id in r.unlocked }
        val sung = Achievements.GENRES.count { it.id in r.unlocked }
        findViewById<TextView>(R.id.achAvatar).avatar(r.name)
        findViewById<TextView>(R.id.achTitle).text = "🏅 ${r.name.uppercase()}"
        findViewById<TextView>(R.id.achSub).text =
            "$main of $total achievements  ·  $sung of ${Achievements.GENRES.size} genres  ·  ${if (isTouch) "swipe" else "▲▼"} to scroll"
        findViewById<TextView>(R.id.achStats).text = statsText(Stats.of(r, board.all()))
        val grid = findViewById<LinearLayout>(R.id.achGrid)
        grid.removeAllViews()
        // Unlocked first (oldest first), then the ones still to get; 5 a row so all fit on screen
        val sorted = Achievements.MAIN.filter { it.id in r.unlocked }.sortedBy { r.unlocked[it.id] } +
            Achievements.MAIN.filter { it.id !in r.unlocked }
        sorted.chunked(5).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { a ->
                val got = r.unlocked[a.id]
                // Height: the row's tallest tile (unlocked ones show the date as well as how they were won)
                row.addView(achievementTile(a, got?.let { "✓ " + Achievements.dateOf(it) }),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(px(3), px(2), px(3), px(2)) })
            }
            repeat(5 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, px(48), 1f).apply { setMargins(px(3), 0, px(3), 0) }) }
            grid.addView(row)
        }
        // Genres: a chip for each, lit up once they've sung it
        val chips = findViewById<LinearLayout>(R.id.achGenres)
        chips.removeAllViews()
        Achievements.GENRES.zip(Genres.ALL).chunked(GENRES_PER_ROW).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (a, g) ->
                val got = r.unlocked[a.id]
                row.addView(TextView(this).apply {
                    text = "${g.icon} ${g.chip}"
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(getColor(if (got != null) R.color.text_main else R.color.text_dim))
                    alpha = if (got != null) 1f else 0.55f
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = px(15).toFloat()
                        setColor(if (got != null) 0x33FFD23F else 0x14FFFFFF)
                        setStroke(px(1), if (got != null) getColor(R.color.gold) else 0x33FFFFFF)
                    }
                    contentDescription = "${g.chip}: " + (got?.let { "${a.title}, sung ${Achievements.dateOf(it)}" } ?: "not sung yet")
                }, LinearLayout.LayoutParams(0, px(28), 1f).apply { setMargins(px(3), px(3), px(3), px(3)) })
            }
            repeat(GENRES_PER_ROW - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, px(28), 1f).apply { setMargins(px(3), 0, px(3), 0) }) }
            chips.addView(row)
        }
        findViewById<ScrollView>(R.id.achScroll).scrollTo(0, 0)
        achLayer.visibility = View.VISIBLE
        popIn(achLayer)
        lockFocus()
        findViewById<View>(R.id.achClose).requestFocus()
    }

    /** "⭐ Best: 94 · “Song”   ❤️ Favourite: …" on one line, "🔥 3 parties in a row …" on the next. */
    private fun statsText(s: PlayerStats): String {
        val first = listOfNotNull(
            s.best?.let { b -> "⭐ Best: ${scoreText(b)}" + (s.bestSong?.let { " · “${it.take(28)}”" } ?: "") },
            s.favourite?.let { "❤️ Favourite: “${it.take(28)}”" + (if (s.favouriteTimes > 1) " (${s.favouriteTimes}×)" else "") },
        )
        val streak = when {
            s.streak > 1 -> "🔥 ${s.streak} parties in a row" + (if (s.bestStreak > s.streak) " (best ${s.bestStreak})" else "")
            s.bestStreak > 1 -> "🔥 Best run: ${s.bestStreak} parties in a row"
            else -> null
        }
        val second = listOfNotNull(streak,
            "🎤 ${s.songs} song${if (s.songs == 1) "" else "s"}",
            "🌙 ${s.parties} part${if (s.parties == 1) "y" else "ies"}")
        return (if (first.isEmpty()) "" else first.joinToString("     ") + "\n") + second.joinToString("     ")
    }

    /** One achievement: in colour with how it was won and the date, or faded with how to win it. */
    private fun achievementTile(a: Achievement, won: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(48)
        setPadding(px(8), px(3), px(6), px(3))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(if (won != null) 0x33FFD23F else 0x14FFFFFF)
            setStroke(px(1), if (won != null) getColor(R.color.gold) else 0x33FFFFFF)
        }
        contentDescription = "${a.title}: ${a.how}. " + (won?.let { "Unlocked ${it.removePrefix("✓ ")}" } ?: "Locked")
        addView(TextView(context).apply {
            text = a.icon; textSize = 22f; gravity = Gravity.CENTER
            alpha = if (won != null) 1f else 0.35f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(px(34), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // Short texts (see Achievements.MAX_HOW) in small type: two lines always fit the tile
            addView(label(a.title.take(Achievements.MAX_TITLE), 12f, if (won != null) R.color.text_main else R.color.text_dim, bold = true))
            addView(label(a.how.take(Achievements.MAX_HOW), 10f, if (won != null) R.color.text_main else R.color.text_dim).apply {
                maxLines = 2; setLineSpacing(0f, 0.92f)
                if (won != null) alpha = 0.8f
            })
            if (won != null) addView(label(won, 9.5f, R.color.gold, bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private var singerReturnFocus: View? = null

    private fun hideSinger() {
        achLayer.visibility = View.GONE
        lockFocus()
        singerReturnFocus?.takeIf { it.isShown }?.requestFocus() ?: findViewById<View>(R.id.tabAch).requestFocus()
        singerReturnFocus = null
    }

    private fun podiumColumn(e: Entry, place: Int): View {
        val heights = intArrayOf(56, 42, 30)   // lower steps leave room for the full list of 8 below
        val medals = arrayOf("🥇", "🥈", "🥉")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                avatar(e.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt())
                textSize = 24f; paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(px(46), px(46)))
            addView(label(e.name, 17f, R.color.text_main, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label(scoreText(e.score), 26f, R.color.gold, bold = true).apply { gravity = Gravity.CENTER })
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
    // 🎲 Surprise me: a random song when nobody knows what to sing
    // =====================================================================================

    private fun setUpDice(browser: SongBrowser) {
        browser.dice = { Surprise.roll(search.country, history.all().map { it.song.title.lowercase() }) }
        // No sounds over a song (the queue panel has the dice too)
        browser.rolling = { if (mode != Mode.SINGING) sfx.play(sfx.tick, 0.3f) }
        browser.onSurprise = { song, _ ->
            if (mode != Mode.SINGING) sfx.play(sfx.reveal, 0.8f)
            toast("🎲 How about “${shortTitle(song.title).take(45)}”? ${if (isTouch) "Tap" else "OK"} to sing it!")
        }
    }

    // =====================================================================================
    // 🌙 Close the night: tonight's awards one by one, then the podium and a summary.
    // Nothing is deleted: it's a show, and it can be watched again.
    // =====================================================================================

    private var recapAwards: List<Award> = emptyList()
    private var recapTop: List<Entry> = emptyList()
    private var recapStats: NightStats? = null
    private var recapStep = -1
    /** Bumped at every step: delayed effects from an earlier step check it and do nothing. */
    private var recapGen = 0
    private var recapCount: ValueAnimator? = null

    private val recapAuto = Runnable { if (recapLayer.isShown) recapGo(recapStep + 1) }

    private fun showRecap() {
        val entries = board.tonightAll()
        if (entries.isEmpty()) {
            toast("🌙 Nobody has sung tonight yet. Sing first, then close the night!")
            return
        }
        val tonightsCheers = cheers.tonight()
        val night = Achievements.nightOf(System.currentTimeMillis())
        val unlocked = runCatching {
            achievements.singers().associate { r -> r.name to r.unlocked.values.count { it == night } }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
        recapAwards = Recap.awards(entries, tonightsCheers, unlocked, cheers.songsTonight())
        recapTop = Recap.podium(entries)
        recapStats = Recap.stats(entries, tonightsCheers)
        overlayReturnFocus = currentFocus
        hideKeyboard()
        recapLayer.visibility = View.VISIBLE
        stageBg.animating = false             // the recap has its own stage; don't draw two
        lockFocus()
        recapGo(0)
    }

    private fun hideRecap() {
        recapGen++
        recapStep = -1
        ui.removeCallbacks(recapAuto)
        recapCount?.cancel()
        sfx.stop(sfx.drumroll)
        confetti.clear()
        recapLayer.animate().cancel()
        recapLayer.visibility = View.GONE
        findViewById<LinearLayout>(R.id.recapPodium).removeAllViews()
        findViewById<LinearLayout>(R.id.recapEndGrid).removeAllViews()
        stageBg.animating = mode != Mode.SINGING
        restoreFocus()
        if (currentFocus?.isShown != true) findViewById<View>(R.id.btnRecap).requestFocus()
    }

    /** Step 0 is the title, then one step per award, the podium, and the summary at the end. */
    private fun recapGo(step: Int) {
        recapGen++
        ui.removeCallbacks(recapAuto)
        recapCount?.cancel()
        sfx.stop(sfx.drumroll)
        stageBg.animating = false
        val last = recapAwards.size + (if (recapTop.isNotEmpty()) 2 else 1)
        recapStep = step.coerceIn(0, last)
        val podiumStep = recapAwards.size + 1
        findViewById<TextView>(R.id.recapHint).text =
            if (recapStep == last) "" else if (isTouch) "Tap for the next one" else "OK ▶ next   ·   ◀ back   ·   BACK to leave"
        when {
            recapStep == 0 -> recapIntro()
            recapStep <= recapAwards.size -> recapAward(recapStep - 1)
            recapStep == podiumStep && recapTop.isNotEmpty() -> recapPodium()
            else -> recapEnd()
        }
    }

    private fun recapStatsLine(fraction: Float = 1f): String {
        val st = recapStats ?: return ""
        fun n(v: Int) = Math.round(v * fraction)
        return listOfNotNull(
            "🎤 ${n(st.songs)} ${if (st.songs == 1) "song" else "songs"}",
            "🙋 ${n(st.singers)} ${if (st.singers == 1) "singer" else "singers"}",
            if (st.duets > 0) "👫 ${n(st.duets)} ${if (st.duets == 1) "duet" else "duets"}" else null,
            if (st.cheers > 0) "📣 ${n(st.cheers)} cheers" else null,
        ).joinToString("   ·   ")
    }

    /** Fills the award card and flips it in. With a [winner], the name is revealed after a short drumroll. */
    /** One recap card; [song]: the winner is a song title (a 🎵 instead of initials, smaller type). */
    private fun recapCard(kicker: String, icon: String, title: String, winner: String?, detail: String, song: Boolean = false) {
        val gen = recapGen
        findViewById<View>(R.id.recapEnd).visibility = View.GONE
        findViewById<LinearLayout>(R.id.recapPodium).apply { removeAllViews(); visibility = View.GONE }
        val card = findViewById<View>(R.id.recapCard)
        val iconView = findViewById<TextView>(R.id.recapIcon)
        val winnerRow = findViewById<View>(R.id.recapWinner)
        val detailView = findViewById<TextView>(R.id.recapDetail)
        val rays = findViewById<RaysView>(R.id.recapRays)
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.recapKicker).text = kicker
        iconView.text = icon
        iconView.visibility = if (icon.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.recapTitle).apply {
            animate().cancel()
            text = title
            scaleX = 1f; scaleY = 1f
            setShadowLayer(26f, 0f, 0f, getColor(R.color.neon_pink))
        }
        detailView.text = detail
        detailView.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        winnerRow.visibility = if (winner == null) View.GONE else View.VISIBLE
        card.contentDescription = listOfNotNull(title, winner, detail.ifEmpty { null }).joinToString(". ")

        // The card flips in
        card.animate().cancel()
        card.cameraDistance = 9000 * resources.displayMetrics.density
        card.alpha = 0f; card.rotationY = -75f; card.scaleX = 0.82f; card.scaleY = 0.82f
        card.animate().alpha(1f).rotationY(0f).scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(1.3f)).setDuration(560).start()
        iconView.animate().cancel()
        iconView.scaleX = 0f; iconView.scaleY = 0f; iconView.rotation = -30f
        iconView.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(160)
            .setInterpolator(OvershootInterpolator(3f)).setDuration(500).start()
        sfx.play(sfx.pop, 0.7f)
        rays.animate().cancel()
        rays.animate().alpha(0f).setDuration(250).start()

        if (winner == null) {
            detailView.alpha = 0f
            detailView.animate().alpha(1f).setStartDelay(500).setDuration(400).start()
            return
        }
        // Drumroll… then the name bursts in, in the winner's own colour, with rays and confetti
        findViewById<TextView>(R.id.recapAvatar).apply { if (song) { text = "🎵"; background = null } else avatar(winner) }
        findViewById<TextView>(R.id.recapName).apply {
            text = if (song) "“$winner”" else winner
            textSize = if (song) 36f else 50f
            setTextColor(colorFor(winner))
        }
        winnerRow.animate().cancel()
        winnerRow.alpha = 0f; winnerRow.scaleX = 0.4f; winnerRow.scaleY = 0.4f
        detailView.animate().cancel()
        detailView.alpha = 0f
        sfx.play(sfx.drumroll, 0.55f)
        ui.postDelayed({
            if (gen != recapGen) return@postDelayed
            sfx.stop(sfx.drumroll)
            sfx.play(sfx.reveal)
            winnerRow.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.6f)).setDuration(480).start()
            detailView.animate().alpha(1f).setStartDelay(350).setDuration(400).start()
            rays.color = colorFor(winner)
            rays.animate().alpha(1f).setDuration(500).start()
            confetti.burst(90)
        }, RECAP_REVEAL_MS)
    }

    private fun recapIntro() {
        val night = Achievements.nightOf(System.currentTimeMillis())
        recapCard("KARAOKE NIGHT  ·  ${Achievements.dateOf(night).uppercase()}", "🌙", "THAT'S A WRAP!", null, recapStatsLine(0f))
        val gen = recapGen
        sfx.play(sfx.fanfare, 0.8f)
        confetti.burst(160)
        // The night's numbers count up
        recapCount = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            startDelay = 500
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { if (gen == recapGen) findViewById<TextView>(R.id.recapDetail).text = recapStatsLine(it.animatedFraction) }
            start()
        }
        ui.postDelayed(recapAuto, 4500)
    }

    private fun recapAward(i: Int) {
        val a = recapAwards[i]
        recapCard("AWARD ${i + 1} OF ${recapAwards.size}", a.icon, a.title.uppercase(), a.winner, a.detail, song = a.song)
        ui.postDelayed(recapAuto, RECAP_REVEAL_MS + 3800)
    }

    /** 3rd, then 2nd, then a long drumroll… and the singer of the night, with everything we've got. */
    private fun recapPodium() {
        recapCard("AND NOW, THE MOMENT YOU'VE BEEN WAITING FOR", "", "🏆 TONIGHT'S TOP 3", null, "")
        val gen = recapGen
        val podium = findViewById<LinearLayout>(R.id.recapPodium)
        podium.visibility = View.VISIBLE
        // Laid out 2nd, 1st, 3rd, like a real podium
        val columns = HashMap<Int, View>()
        for (place in listOf(1, 0, 2)) {
            val e = recapTop.getOrNull(place) ?: continue
            val col = recapColumn(e, place)
            columns[place] = col
            col.alpha = 0f
            col.translationY = px(70).toFloat()
            podium.addView(col)
        }
        fun rise(place: Int, at: Long, big: Boolean) = ui.postDelayed({
            if (gen != recapGen) return@postDelayed
            val col = columns[place] ?: return@postDelayed
            col.animate().alpha(1f).translationY(0f).setInterpolator(OvershootInterpolator(if (big) 2.2f else 1.6f)).setDuration(600).start()
            if (!big) { sfx.play(sfx.reveal, 0.8f); confetti.burst(50) }
        }, at)
        val third = recapTop.size >= 3
        val second = recapTop.size >= 2
        if (third) rise(2, 700, big = false)
        if (second) rise(1, if (third) 2100 else 700, big = false)
        val drumAt = if (second) (if (third) 3400L else 2000L) else 600L
        val firstAt = drumAt + 2800
        ui.postDelayed({ if (gen == recapGen) sfx.play(sfx.drumroll) }, drumAt)
        ui.postDelayed({
            if (gen != recapGen) return@postDelayed
            sfx.stop(sfx.drumroll)
            sfx.play(sfx.fanfare)
            sfx.play(sfx.applause, 0.9f)
            val champ = recapTop[0]
            findViewById<TextView>(R.id.recapKicker).text = "SINGER OF THE NIGHT"
            findViewById<TextView>(R.id.recapTitle).apply {
                text = "👑 ${champ.name.uppercase()}"
                setShadowLayer(26f, 0f, 0f, getColor(R.color.gold))
                scaleX = 1.5f; scaleY = 1.5f
                animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(600).start()
            }
            findViewById<View>(R.id.recapCard).contentDescription = "Singer of the night: ${champ.name}, ${scoreText(champ.score)} points"
            rise(0, 0, big = true)
            columns[0]?.findViewWithTag<View>("crown")?.apply {
                animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(450).setInterpolator(OvershootInterpolator(4f)).setDuration(500).start()
            }
            findViewById<RaysView>(R.id.recapRays).apply { color = getColor(R.color.gold); animate().alpha(1f).setDuration(600).start() }
            confetti.burst(260)
            ui.postDelayed({ if (gen == recapGen) confetti.burst(160) }, 1000)
            ui.postDelayed({ if (gen == recapGen) confetti.burst(120) }, 2200)
        }, firstAt)
        ui.postDelayed(recapAuto, firstAt + 7000)
    }

    /** A podium step: crown (1st only), avatar, name, score and a block in the singer's colour. */
    private fun recapColumn(e: Entry, place: Int): View {
        val heights = intArrayOf(120, 86, 62)
        val medals = arrayOf("🥇", "🥈", "🥉")
        val big = place == 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "${listOf("First", "Second", "Third")[place]} place: ${e.name}, ${scoreText(e.score)} points"
            addView(TextView(context).apply {
                tag = "crown"
                text = if (big) "👑" else ""
                textSize = 34f
                gravity = Gravity.CENTER
                alpha = 0f; scaleX = 0.2f; scaleY = 0.2f
            })
            addView(TextView(context).apply {
                avatar(e.name); gravity = Gravity.CENTER; setTextColor(0xFF0B0614.toInt())
                textSize = if (big) 30f else 24f; paint.isFakeBoldText = true
            }, LinearLayout.LayoutParams(px(if (big) 64 else 50), px(if (big) 64 else 50)))
            addView(label(e.name, if (big) 22f else 18f, R.color.text_main, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
            addView(label(scoreText(e.score), if (big) 32f else 26f, R.color.gold, bold = true).apply {
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = medals[place]
                textSize = if (big) 38f else 30f
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(colorFor(e.name) and 0x00FFFFFF or 0xCC000000.toInt(), 0x331A1030)
                ).apply { val r = px(14).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            }, LinearLayout.LayoutParams(px(if (big) 200 else 170), px(heights[place])).apply { topMargin = px(6) })
        }.also { it.layoutParams = LinearLayout.LayoutParams(px(if (big) 210 else 180), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(px(8), 0, px(8), 0) } }
    }

    /** The end: every award at a glance, and the way back to the party. */
    private fun recapEnd() {
        findViewById<View>(R.id.recapCard).visibility = View.GONE
        findViewById<LinearLayout>(R.id.recapPodium).apply { removeAllViews(); visibility = View.GONE }
        findViewById<RaysView>(R.id.recapRays).animate().alpha(0.35f).setDuration(500).start()
        findViewById<TextView>(R.id.recapEndSub).text = "Thanks for singing, everyone!   ${recapStatsLine()}"
        val tiles = ArrayList<Award>()
        recapTop.firstOrNull()?.let { tiles += Award("👑", "Singer of the night", it.name, "") }
        tiles += recapAwards
        val grid = findViewById<LinearLayout>(R.id.recapEndGrid)
        grid.removeAllViews()
        tiles.chunked(4).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
            row.forEach { a ->
                line.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.panel_bg)
                    setPadding(px(10), px(8), px(10), px(8))
                    contentDescription = "${a.title}: ${a.winner}"
                    addView(label(a.icon, 24f, R.color.text_main))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(px(8), 0, 0, 0)
                        addView(label(a.title.uppercase(), 10f, R.color.neon_cyan, bold = true).apply { letterSpacing = 0.08f })
                        addView(label(a.winner, 16f, R.color.text_main, bold = true).apply { setTextColor(colorFor(a.winner)) })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(px(196), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(px(5), px(5), px(5), px(5)) })
            }
            grid.addView(line)
        }
        val end = findViewById<View>(R.id.recapEnd)
        end.visibility = View.VISIBLE
        popIn(end)
        for (i in 0 until grid.childCount) {
            val line = grid.getChildAt(i) as ViewGroup
            for (j in 0 until line.childCount) line.getChildAt(j).apply {
                alpha = 0f; translationY = px(20).toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(200L + (i * 4 + j) * 90L).setDuration(320).start()
            }
        }
        sfx.play(sfx.applause, 0.7f)
        confetti.burst(140)
        lockFocus()
        findViewById<View>(R.id.recapDone).requestFocus()
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
        listOf(pickerLayer, sheetLayer, boardLayer, qrLayer, volumeLayer, aboutLayer, achLayer).forEach { it.visibility = View.GONE }
        if (recapLayer.isShown) hideRecap()
        if (countryLayer.isShown) hideCountries()
        hideKeyboard()
        lockFocus()
    }

    private fun anyOverlayOpen() = pickerLayer.isShown || sheetLayer.isShown || boardLayer.isShown || qrLayer.isShown ||
        countryLayer.isShown || volumeLayer.isShown || aboutLayer.isShown || achLayer.isShown || recapLayer.isShown

    /**
     * While a pop-up is open, nothing behind it can take the remote's focus: ▲ ▼ ◀ ▶ stay
     * inside the pop-up instead of wandering into the song list.
     */
    private fun lockFocus() {
        val block = anyOverlayOpen()
        val rule = if (block) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        listOf(lobby, queuePanel, hud, intermission).forEach { (it as? ViewGroup)?.descendantFocusability = rule }
        // Pop-ups stacked on another (a sheet over the picker) only keep the top one reachable
        val top = listOf(recapLayer, volumeLayer, aboutLayer, achLayer, sheetLayer, countryLayer, qrLayer, boardLayer, pickerLayer).firstOrNull { it.isShown }
        listOf(pickerLayer, boardLayer, qrLayer, countryLayer, sheetLayer, volumeLayer, aboutLayer, achLayer, recapLayer).forEach {
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

    /**
     * Opens a pop-up: its card slides up into place (a plain view slides up a touch).
     *
     * Kept cheap for the Shield: only position moves, which the GPU does without redrawing,
     * re-measuring or re-drawing text. No fades of big layers (each one needs a full-screen
     * off-screen buffer) and no scaling of text-heavy panels (text gets redrawn at every size).
     */
    private fun popIn(v: View) {
        v.animate().cancel()
        v.alpha = 1f
        val card = when {
            v !is ViewGroup -> v
            v.background != null && v.childCount > 0 -> v.getChildAt(0)
            else -> return
        }
        card.animate().cancel()
        card.translationY = px(if (card !== v) 22 else 12).toFloat()
        card.animate().translationY(0f).setStartDelay(0).setDuration(220).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    /** List rows slide in one after another (just the first few: the rest start off screen). */
    private fun cascade(list: ViewGroup, max: Int = 10) {
        for (i in 0 until minOf(max, list.childCount)) slideIn(list.getChildAt(i), i * 25L)
    }

    /** Position only (no fade), so it costs the Shield next to nothing. */
    private fun slideIn(row: View, delay: Long = 0) {
        row.animate().cancel()
        row.translationX = px(28).toFloat()
        row.animate().translationX(0f).setStartDelay(delay).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
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
        /** The "Starting in 10…" warning: the last part of [AUTOSTART_MS]. */
        const val AUTOSTART_WARN_MS = 10_000L
        /** How long "Ana sings this for Bia" shows before the song. */
        const val DEDICATION_MS = 5_000L
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
        /** How long a video may take to start before it gets a nudge (see startWatch). */
        const val START_CHECK_MS = 3500L
        /** Buffering this long mid-song makes the TV check its internet. */
        const val STALL_MS = 6000L
        /** At most this many phones vote on one song. */
        const val MAX_VOTES = 300
        /** In the night recap, how long the drumroll lasts before each winner's name appears. */
        const val RECAP_REVEAL_MS = 1500L
        /** Genre chips per row on a singer's achievements (two rows for all 17). */
        const val GENRES_PER_ROW = 9
        /** Rows in each hall of fame ranking (it scrolls). */
        const val BOARD_ROWS = 50
        /** Skipping after this much of the song earns "Huh?!". */
        const val HUH_FRACTION = 0.9
        /** How often the hype bar drains while nobody cheers. */
        const val HYPE_TICK_MS = 500L
        /** The special-date hello waits for the intro to finish. */
        const val THEME_HELLO_MS = 2500L
        const val PROJECT_URL = "https://github.com/aabramos/karaoke-night/"
        val STAGE_NAMES = listOf(
            "Disco Diva", "Rock Legend", "Mystery Voice", "Mic Master", "Golden Voice", "Pop Idol",
            "Shower Singer", "Night Owl", "Velvet Voice", "Funky Star", "Karaoke King", "Karaoke Queen",
            "Rockstar", "Dancing Queen", "Soul Sister", "Mr. Groove"
        )
    }
}
