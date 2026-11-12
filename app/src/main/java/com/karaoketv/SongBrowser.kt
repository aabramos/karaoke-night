package com.karaoketv

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService

/**
 * Search box + quick-pick chips + results. Used twice: as a grid in the lobby and as a
 * compact list in the side panel during a song. Plain views (no RecyclerView) to keep the
 * app small; results are capped at 30 so this stays light.
 */
class SongBrowser(
    private val activity: Activity,
    private val search: SongSearch,
    private val io: ExecutorService,
    private val searchBox: EditText,
    private val chips: LinearLayout,
    private val status: TextView,
    private val scroll: ScrollView,
    private val results: LinearLayout,
    private val columns: Int,
    /** A song was chosen; the second value is the search or quick pick that found it (null for "🕘 Played"). */
    private val onPick: (Song, String?) -> Unit,
) {
    private var generation = 0
    private var focusOnResults = false
    private val inflater = LayoutInflater.from(activity)
    private val density = activity.resources.displayMetrics.density
    var lastQuery: String? = null
        private set
    /** Called after each successful search with whether anything was found. */
    var onResults: ((Boolean) -> Unit)? = null
    // Declared before init, which fills it
    private var picks: List<Pair<String, String>> = emptyList()
    /** The recently sung songs, for the "🕘 Played" chip (shown first when set). */
    var played: (() -> List<Played>)? = null
    /** Rolls a random song for the "🎲 Surprise me" chip (shown after "🕘 Played" when set). */
    var dice: (() -> Surprise.Roll)? = null
    /** The dice landed on [song] (for a sound and a toast); [rolling] is called for each name that flashes past. */
    var onSurprise: ((Song, Surprise.Roll) -> Unit)? = null
    var rolling: (() -> Unit)? = null
    /** A search failed for lack of internet (not a YouTube answer): the screen checks the connection. */
    var onOffline: (() -> Unit)? = null
    /** What to run again once the internet is back, if the last search failed for lack of it. */
    private var retry: (() -> Unit)? = null
    private val surpriseChip get() = if (played != null) 1 else 0
    private val chipOffset get() = (if (played != null) 1 else 0) + (if (dice != null) 1 else 0)

    init {
        searchBox.setOnEditorActionListener { v, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                val q = v.text.toString().trim()
                if (q.isNotEmpty()) {
                    hideKeyboard()
                    selectChip(-1)
                    focusOnResults = true
                    run(q)
                }
                true
            } else false
        }
        setPicks(Countries.INTERNATIONAL_PICKS)
    }

    /** Replaces the quick-pick chips (they change with the party's country). */
    fun setPicks(list: List<Pair<String, String>>) {
        picks = list
        chips.removeAllViews()
        (chips.parent as? android.widget.HorizontalScrollView)?.scrollTo(0, 0)
        if (played != null) addChip("🕘 Played") { showPlayed() }
        if (dice != null) addChip("🎲 Surprise me") { surprise() }
        list.forEachIndexed { i, (label, query) -> addChip(label) { selectChip(i); run(query) } }
    }

    private fun addChip(label: String, onClick: () -> Unit) {
        val chip = TextView(activity).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.chip_bg)
            isFocusable = true
            isClickable = true
            setPadding(px(18), px(9), px(18), px(9))
            setOnClickListener { onClick() }
        }
        chips.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = px(10) })
    }

    /** The last songs that were sung, straight from memory (no internet needed). */
    fun showPlayed() {
        generation++                        // a search still loading must not replace this list
        lastQuery = null
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = i == 0
        val list = played?.invoke().orEmpty()
        render(list.map { it.song }, list.associate { it.song.id to History.label(it) })
        if (list.isEmpty()) showStatus("Nothing has been sung yet.\nSongs appear here after they're played.")
        onResults?.invoke(list.isNotEmpty())
    }

    /**
     * "🎲 Surprise me": song names flash past like a slot machine while the dice's song is
     * searched, then the results show with the surprise first (and focused). Press it again to re-roll.
     */
    fun surprise() {
        val roll = dice?.invoke() ?: return
        val gen = ++generation
        lastQuery = roll.query
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = i == surpriseChip
        scroll.animate().alpha(0.3f).setDuration(150).start()
        var result: Result<List<Song>>? = null
        val started = android.os.SystemClock.uptimeMillis()
        val spin = object : Runnable {
            override fun run() {
                if (gen != generation || activity.isFinishing) return
                val r = result
                val elapsed = android.os.SystemClock.uptimeMillis() - started
                if (r != null && elapsed >= SPIN_MS) { landSurprise(roll, r); return }
                if (elapsed > SPIN_MS + 1500) {
                    // A slow connection: stop the slot machine (and its ticking) and just wait
                    showStatus("🎲  Looking for “${roll.label}”…")
                    status.postDelayed(this, 250)
                    return
                }
                showStatus("🎲  ${Surprise.teaser()}")
                rolling?.invoke()
                // Slows down towards the end, like a wheel coming to a stop
                status.postDelayed(this, if (r != null && elapsed > SPIN_MS - 500) 170L else 85L)
            }
        }
        status.post(spin)
        val engine = search
        val self = WeakReference(this)
        io.execute {
            val r = runCatching { engine.search(roll.query) }
            activity.runOnUiThread { if (self.get()?.generation == gen) result = r }
        }
    }

    private fun landSurprise(roll: Surprise.Roll, result: Result<List<Song>>) {
        result.onSuccess { found ->
            val songs = Surprise.arrange(roll, found)
            val first = songs.firstOrNull()
            render(songs, if (first != null) mapOf(first.id to "🎲 Surprise pick · ${roll.label}") else emptyMap())
            if (first == null) { showStatus("🎲 No karaoke version of “${roll.label}” found.\nRoll again!"); return }
            focusFirst()
            onSurprise?.invoke(first, roll)
        }.onFailure { e ->
            render(emptyList())
            showFailure(e) { surprise() }
        }
    }

    /** Explains a failed search. Without internet it also says it will try again by itself. */
    private fun showFailure(e: Throwable, again: () -> Unit) {
        if (e is SearchException) { showStatus("😕 ${e.message}"); return }
        retry = again
        showStatus(OFFLINE_TEXT)
        onOffline?.invoke()
    }

    /** The internet is back: re-run the search that failed without it (if it's still on screen). */
    fun retryIfFailed() {
        val again = retry ?: return
        retry = null
        if (status.visibility == View.VISIBLE && status.text == OFFLINE_TEXT) again()
    }

    fun runQuickPick(index: Int) {
        val pick = picks.getOrNull(index) ?: return
        selectChip(index)
        run(pick.second)
    }

    /** Search typed by the user (or by voice): fills the box and jumps to the results. */
    fun searchFor(text: String) {
        searchBox.setText(text)
        selectChip(-1)
        focusOnResults = true
        run(text)
    }

    fun run(query: String) {
        val gen = ++generation
        lastQuery = query
        retry = null
        showStatus("🎵  Searching…")
        scroll.animate().alpha(0.3f).setDuration(150).start()   // old results fade back while searching
        // The background task only keeps a weak link back to this screen, so a slow network
        // request can never hold a closed screen in memory.
        val engine = search
        val self = WeakReference(this)
        io.execute {
            val result = runCatching { engine.search(query) }
            self.get()?.deliver(gen, result)
        }
    }

    private fun deliver(gen: Int, result: Result<List<Song>>) {
        activity.runOnUiThread {
            if (gen != generation || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            result.onSuccess { songs ->
                render(songs)
                if (songs.isEmpty()) showStatus("No karaoke versions found.\nTry another song or artist.")
                if (focusOnResults && songs.isNotEmpty()) focusFirst()
                focusOnResults = false
                onResults?.invoke(songs.isNotEmpty())
            }.onFailure { e ->
                render(emptyList())
                focusOnResults = false
                val query = lastQuery
                showFailure(e) { if (query != null) run(query) }
            }
        }
    }

    fun focusFirst(): Boolean {
        val first = firstCard() ?: return chips.getChildAt(0)?.requestFocus() ?: false
        return first.requestFocus()
    }

    fun hasResults() = results.childCount > 0

    /** Drops thumbnails/views (e.g. when memory is low or the panel closes). */
    fun clear() {
        generation++
        results.removeAllViews()
        scroll.animate().cancel()
        scroll.alpha = 1f
        status.visibility = View.GONE
    }

    private fun firstCard(): View? {
        val row = results.getChildAt(0) ?: return null
        return if (columns > 1) (row as? ViewGroup)?.getChildAt(0) else row
    }

    /** [notes]: a line to show instead of the channel, by video ID (used for "🕘 Played"). */
    private fun render(songs: List<Song>, notes: Map<String, String> = emptyMap()) {
        results.removeAllViews()
        scroll.scrollTo(0, 0)
        scroll.animate().cancel()
        scroll.alpha = 1f
        status.visibility = View.GONE
        if (columns > 1) {
            songs.chunked(columns).forEach { rowSongs ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rowSongs.forEach { row.addView(card(R.layout.item_song, row, it, notes[it.id])) }
                // keep card widths equal on the last, shorter row
                repeat(columns - rowSongs.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(px(7), 0, px(7), 0) })
                }
                results.addView(row)
            }
        } else {
            songs.forEach { results.addView(card(R.layout.item_song_row, results, it, notes[it.id])) }
        }
    }

    private fun card(layout: Int, parent: ViewGroup, song: Song, note: String? = null): View {
        val v = inflater.inflate(layout, parent, false)
        v.findViewById<View>(R.id.thumbFrame).clipToOutline = true
        v.findViewById<TextView>(R.id.title).text = song.title
        v.findViewById<TextView>(R.id.channel).text =
            note ?: listOf(song.channel, viewsLabel(song.views)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        v.findViewById<TextView>(R.id.dur).apply {
            text = song.duration
            visibility = if (song.duration.isEmpty()) View.GONE else View.VISIBLE
        }
        ImageLoader.load(song.thumb, v.findViewById<ImageView>(R.id.thumb))
        v.setOnClickListener { onPick(song, lastQuery) }
        v.setOnFocusChangeListener { view, focused ->
            val s = if (focused) (if (columns > 1) 1.07f else 1.03f) else 1f
            view.animate().scaleX(s).scaleY(s).translationZ(if (focused) px(10).toFloat() else 0f)
                .setDuration(130).start()
        }
        return v
    }

    /** [index] into the quick picks (-1 = none); the "🕘 Played" chip, when shown, comes first. */
    private fun selectChip(index: Int) {
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = index >= 0 && i == index + chipOffset
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    private fun px(dp: Int) = (dp * density).toInt()

    private companion object {
        /** How long the names flash past, at least (the search usually takes about that long anyway). */
        const val SPIN_MS = 1600L
        const val OFFLINE_TEXT = "📡 No internet, so no songs right now.\nThe search runs again by itself when it's back."
    }
}
