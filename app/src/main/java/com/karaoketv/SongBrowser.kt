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
    private val onPick: (Song) -> Unit,
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
        list.forEachIndexed { i, (label, query) ->
            val chip = TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.chip_bg)
                isFocusable = true
                isClickable = true
                setPadding(px(18), px(9), px(18), px(9))
                setOnClickListener { selectChip(i); run(query) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = px(10) })
        }
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
                showStatus(
                    if (e is SearchException) "😕 ${e.message}"
                    else "😕 Can't reach YouTube.\nCheck the TV's internet connection."
                )
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

    private fun render(songs: List<Song>) {
        results.removeAllViews()
        scroll.scrollTo(0, 0)
        scroll.animate().cancel()
        scroll.alpha = 1f
        status.visibility = View.GONE
        if (columns > 1) {
            songs.chunked(columns).forEach { rowSongs ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                rowSongs.forEach { row.addView(card(R.layout.item_song, row, it)) }
                // keep card widths equal on the last, shorter row
                repeat(columns - rowSongs.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(px(7), 0, px(7), 0) })
                }
                results.addView(row)
            }
        } else {
            songs.forEach { results.addView(card(R.layout.item_song_row, results, it)) }
        }
    }

    private fun card(layout: Int, parent: ViewGroup, song: Song): View {
        val v = inflater.inflate(layout, parent, false)
        v.findViewById<View>(R.id.thumbFrame).clipToOutline = true
        v.findViewById<TextView>(R.id.title).text = song.title
        v.findViewById<TextView>(R.id.channel).text =
            listOf(song.channel, viewsLabel(song.views)).filter { it.isNotEmpty() }.joinToString("  ·  ")
        v.findViewById<TextView>(R.id.dur).apply {
            text = song.duration
            visibility = if (song.duration.isEmpty()) View.GONE else View.VISIBLE
        }
        ImageLoader.load(song.thumb, v.findViewById<ImageView>(R.id.thumb))
        v.setOnClickListener { onPick(song) }
        v.setOnFocusChangeListener { view, focused ->
            val s = if (focused) (if (columns > 1) 1.07f else 1.03f) else 1f
            view.animate().scaleX(s).scaleY(s).translationZ(if (focused) px(10).toFloat() else 0f)
                .setDuration(130).start()
        }
        return v
    }

    private fun selectChip(index: Int) {
        for (i in 0 until chips.childCount) chips.getChildAt(i).isSelected = i == index
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
}
