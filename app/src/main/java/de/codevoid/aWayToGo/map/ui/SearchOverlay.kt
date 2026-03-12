package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import de.codevoid.aWayToGo.search.RecentSearches
import de.codevoid.aWayToGo.search.SearchResult

/**
 * Handle returned by [buildSearchOverlay] to imperatively push state into the overlay.
 *
 * @property root          The overlay view to add to the root layout.
 * @property showResults   Replace the result list with [results].
 * @property showLoading   Replace the result list with a spinner.
 * @property showError     Replace the result list with an error message.
 * @property clearResults  Remove all results and hide the result panel.
 * @property prepareForOpen Refresh shortcuts so the panel is ready when made visible.
 *                          Does NOT focus the field or show the keyboard — the user
 *                          activates the keyboard by tapping the search field.
 * @property hideKeyboard  Dismiss the soft keyboard without closing the search panel.
 */
class SearchOverlayResult(
    val root: FrameLayout,
    val showResults: (List<SearchResult>) -> Unit,
    val showLoading: () -> Unit,
    val showError: () -> Unit,
    val clearResults: () -> Unit,
    val prepareForOpen: () -> Unit,
    val hideKeyboard: () -> Unit,
    val isLocalSearch: () -> Boolean,
    val isGpsAnchor: () -> Boolean,
)

/**
 * Build the search overlay panel.
 *
 * Layout (bottom-anchored, full-width) — visual order from top of screen downward:
 *
 *   ┌────────────────────────────────────────┐
 *   │  [Result name]                         │  results list (hidden until a search runs)
 *   │  [Result name]                         │
 *   │  ────────────────────────────────────  │
 *   │  Recent: [Term] [Loc] [Term2] …        │  shortcuts (HorizontalScrollView)
 *   │  ────────────────────────────────────  │
 *   │  [Search field________________] [Go] ✕ │  input row (sits just above keyboard)
 *   └────────────────────────────────────────┘
 *
 * The result panel starts hidden ([View.GONE]) and becomes visible once a search
 * has been performed.  Results are **not** cleared when the overlay is closed and
 * reopened — the caller must explicitly call [SearchOverlayResult.clearResults] to
 * reset them.
 *
 * @param context          Activity context.
 * @param recentSearches   Storage for recent queries and visited locations.
 * @param locationProvider Returns the current GPS position (lat, lon) or null if unavailable.
 * @param onClose          Called when the ✕ button is tapped.
 * @param onSearch         Called with the query string when Go is tapped / IME action fired.
 * @param onResultClick    Called when the user taps a result row.
 */
fun buildSearchOverlay(
    context: Context,
    recentSearches: RecentSearches,
    locationProvider: () -> Pair<Double, Double>?,
    onClose: () -> Unit,
    onSearch: (String) -> Unit,
    onResultClick: (SearchResult) -> Unit,
): SearchOverlayResult {
    val d = context.resources.displayMetrics.density

    // ── Helper: divider line ──────────────────────────────────────────────────
    fun makeDivider(): View = View(context).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * d).toInt(),
        )
    }

    // ── Helper: small shortcut card ───────────────────────────────────────────
    fun makeShortcutCard(label: String, onClick: () -> Unit): TextView {
        val hPad = (12 * d).toInt()
        val vPad = (6 * d).toInt()
        val radius = 16 * d
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(Color.argb(120, 255, 255, 255))
                },
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(Color.WHITE)
                },
            )
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ── Helper: format distance for display ────────────────────────────────────
    fun formatDistance(meters: Float): String = when {
        meters < 1000 -> "${meters.toInt()} m"
        meters < 10_000 -> "%.1f km".format(meters / 1000f)
        else -> "${(meters / 1000f).toInt()} km"
    }

    // ── Helper: result row ────────────────────────────────────────────────────
    fun makeResultRow(result: SearchResult, onClick: () -> Unit): View {
        val hPad = (16 * d).toInt()
        val vPad = (12 * d).toInt()

        // Build formatted address lines from structured data, falling back to
        // display_name when structured fields are not available.
        val line1: String
        val line2: String?
        val line3: String?
        if (result.road != null) {
            line1 = if (result.houseNumber != null) "${result.road} ${result.houseNumber}" else result.road
            line2 = listOfNotNull(result.postcode, result.city).takeIf { it.isNotEmpty() }?.joinToString(" ")
            line3 = listOfNotNull(result.state, result.country).takeIf { it.isNotEmpty() }?.joinToString(", ")
        } else {
            // Fallback: split display_name at first comma
            val comma = result.displayName.indexOf(',')
            line1 = if (comma > 0) result.displayName.substring(0, comma) else result.displayName
            line2 = if (comma > 0) result.displayName.substring(comma + 1).trim() else null
            line3 = null
        }

        // Build styled text
        val dimColor = Color.argb(180, 255, 255, 255)
        val dimmerColor = Color.argb(130, 255, 255, 255)
        val span = android.text.SpannableStringBuilder().apply {
            append(line1, android.text.style.StyleSpan(Typeface.BOLD),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!line2.isNullOrEmpty()) {
                append("\n")
                val start = length
                append(line2)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(dimColor),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (!line3.isNullOrEmpty()) {
                append("\n")
                val start = length
                append(line3)
                setSpan(android.text.style.RelativeSizeSpan(0.7f), start, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(dimmerColor),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val addressView = TextView(context).apply {
            text = span
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // Right side: direction arrow + distance
        val rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val loc = locationProvider()
            if (loc != null) {
                val results = FloatArray(2)
                Location.distanceBetween(loc.first, loc.second, result.lat, result.lon, results)
                val distMeters = results[0]
                val bearing = results[1]  // degrees east of true north

                addView(TextView(context).apply {
                    text = "➤"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    rotation = bearing - 90f  // ➤ points right (east) by default, subtract 90 to align with north=up
                })
                addView(TextView(context).apply {
                    text = formatDistance(distMeters)
                    setTextColor(dimColor)
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply { setColor(Color.WHITE) },
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(addressView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(rightColumn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins((8 * d).toInt(), 0, 0, 0) })
        }
    }

    // ── Root panel background ─────────────────────────────────────────────────
    val topCornerRadius = 32 * d
    val panelBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(topCornerRadius, topCornerRadius, topCornerRadius, topCornerRadius, 0f, 0f, 0f, 0f)
        setColor(Color.argb(220, 0, 0, 0))
    }

    // ── Keyboard helpers (defined early so goButton can reference them) ───────
    // Defined here rather than near the return so goButton can call hideKeyboard.
    fun showKeyboard(view: android.view.View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard(view: android.view.View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ── Input row (bottom — sits directly above the keyboard) ─────────────────
    val searchField = EditText(context).apply {
        hint = "Search for a place…"
        setHintTextColor(Color.argb(120, 255, 255, 255))
        setTextColor(Color.WHITE)
        textSize = 20f
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16 * d
            setColor(Color.argb(80, 255, 255, 255))
        }
        val fp = (10 * d).toInt()
        val hp = (14 * d).toInt()
        setPadding(hp, fp, hp, fp)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        maxLines = 1
        // Show the keyboard whenever the field gains focus (i.e. when the user
        // taps it).  post() defers until the view is laid out and attached.
        setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) v.post { showKeyboard(v) }
        }
    }

    val goButton = TextView(context).apply {
        text = "Go"
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * d
                setColor(Color.argb(200, 220, 50, 50))
            },
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * d
                setColor(Color.WHITE)
            },
        )
        val hp = (20 * d).toInt()
        val vp = (10 * d).toInt()
        setPadding(hp, vp, hp, vp)
        isClickable = true
        isFocusable = true
    }

    val closeButton = TextView(context).apply {
        text = "✕"
        setTextColor(Color.WHITE)
        textSize = 22f
        gravity = Gravity.CENTER
        val sz = (48 * d).toInt()
        minWidth = sz
        minHeight = sz
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClose() }
    }

    val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (12 * d).toInt()
        setPadding(p, p, p, p)
        addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(goButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins((8 * d).toInt(), 0, 0, 0) })
        addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins((4 * d).toInt(), 0, 0, 0) })
    }

    // ── Toggle buttons row ───────────────────────────────────────────────────
    var localSearch = true
    var gpsAnchor = true
    val toggleRadius = 14 * d

    fun makeToggleBg(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = toggleRadius
        setColor(if (active) Color.argb(200, 220, 50, 50) else Color.argb(80, 255, 255, 255))
    }

    val localToggle = TextView(context).apply {
        text = "Local"
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            makeToggleBg(true),
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = toggleRadius; setColor(Color.WHITE) },
        )
        val hp = (14 * d).toInt()
        val vp = (6 * d).toInt()
        setPadding(hp, vp, hp, vp)
        isClickable = true
        isFocusable = true
    }

    val anchorToggle = TextView(context).apply {
        text = "GPS"
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            makeToggleBg(true),
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = toggleRadius; setColor(Color.WHITE) },
        )
        val hp = (14 * d).toInt()
        val vp = (6 * d).toInt()
        setPadding(hp, vp, hp, vp)
        isClickable = true
        isFocusable = true
    }

    fun updateToggleAppearance(view: TextView, active: Boolean, activeLabel: String, inactiveLabel: String) {
        view.text = if (active) activeLabel else inactiveLabel
        view.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            makeToggleBg(active),
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = toggleRadius; setColor(Color.WHITE) },
        )
    }

    localToggle.setOnClickListener {
        localSearch = !localSearch
        updateToggleAppearance(localToggle, localSearch, "Local", "Global")
    }
    anchorToggle.setOnClickListener {
        gpsAnchor = !gpsAnchor
        updateToggleAppearance(anchorToggle, gpsAnchor, "GPS", "Map")
    }

    val toggleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val hp = (12 * d).toInt()
        val vp = (4 * d).toInt()
        setPadding(hp, vp, hp, vp)
        addView(localToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(anchorToggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins((8 * d).toInt(), 0, 0, 0) })
    }

    // ── Shortcuts row (middle) ────────────────────────────────────────────────
    val shortcutsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val gap = (8 * d).toInt()
        setPadding(gap, 0, gap, 0)
    }

    fun refreshShortcuts(field: EditText) {
        shortcutsContainer.removeAllViews()
        val gap = (8 * d).toInt()
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, gap, 0) }

        // Recent locations first (with 📍 prefix), then the single recent search term
        recentSearches.getLocations().forEach { loc ->
            val card = makeShortcutCard("📍 ${loc.displayName.substringBefore(',')}") {
                onSearch(loc.displayName.substringBefore(','))
                field.text.clear()
            }
            shortcutsContainer.addView(card, LinearLayout.LayoutParams(lp))
        }
        recentSearches.getSearchTerms().forEach { term ->
            val card = makeShortcutCard("🔍 $term") {
                onSearch(term)
                field.text.clear()
            }
            shortcutsContainer.addView(card, LinearLayout.LayoutParams(lp))
        }
    }

    val shortcutsScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(shortcutsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    val shortcutsSection = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val hp = (12 * d).toInt()
        val vp = (8 * d).toInt()
        setPadding(hp, vp, hp, vp)
        addView(shortcutsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    // ── Results area (top — visible above the map content when results exist) ──
    val resultsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val resultsScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(resultsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    // In portrait the result list may grow to 50 % of the screen height so more
    // results are visible without scrolling.  In landscape 250 dp is kept as the
    // cap because vertical space is scarce and the keyboard takes a large chunk.
    val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val resultsMaxHeightPx = if (isPortrait) {
        (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
    } else {
        (250 * d).toInt()
    }

    // Wrapper holds the scroll + a divider below it. Starts hidden.
    val resultsWrapper = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resultsMaxHeightPx,
        ))
        addView(makeDivider())
    }

    // ── Main panel ────────────────────────────────────────────────────────────
    // Visual order from top of screen downward (panel is bottom-anchored):
    //   1. Results list   — hidden until a search is performed
    //   2. Input row      — search field + Go + ✕
    //   3. Toggle row     — Local/Global + GPS/Map toggles
    //   4. Shortcuts row  — sits directly above the system keyboard
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBg
        addView(resultsWrapper, LinearLayout.LayoutParams(       // 1. results
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(inputRow, LinearLayout.LayoutParams(             // 2. input
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(toggleRow, LinearLayout.LayoutParams(            // 3. toggles
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(makeDivider())
        addView(shortcutsSection, LinearLayout.LayoutParams(     // 4. shortcuts
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    val root = FrameLayout(context).apply {
        addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    // ── Wire up search actions ────────────────────────────────────────────────
    // Triggers a search and clears the input field immediately so the user can
    // see the result panel without the typed text lingering.
    fun triggerSearch() {
        val query = searchField.text.toString().trim()
        if (query.isNotEmpty()) {
            onSearch(query)
            searchField.text.clear()
        }
    }

    // After search is triggered collapse the keyboard so the result list has
    // maximum screen space (especially useful in landscape).
    goButton.setOnClickListener {
        triggerSearch()
        hideKeyboard(searchField)
    }
    searchField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
            triggerSearch()
            hideKeyboard(searchField)
            true
        } else false
    }

    // ── State mutators returned to MapActivity ────────────────────────────────

    /** Fade the results wrapper in from invisible. Cancels any in-flight animation first. */
    fun fadeInWrapper() {
        resultsWrapper.fadeIn()
    }

    fun showResults(results: List<SearchResult>) {
        resultsContainer.removeAllViews()
        if (results.isEmpty()) {
            resultsContainer.addView(TextView(context).apply {
                text = "No results found."
                setTextColor(Color.argb(180, 255, 255, 255))
                textSize = 16f
                gravity = Gravity.CENTER
                val p = (16 * d).toInt()
                setPadding(p, p, p, p)
            })
        } else {
            results.forEachIndexed { i, result ->
                if (i > 0) resultsContainer.addView(makeDivider())
                resultsContainer.addView(makeResultRow(result) { onResultClick(result) })
            }
            // Refresh shortcuts now that the new search term has been saved.
            refreshShortcuts(searchField)
        }
        fadeInWrapper()
    }

    fun showLoading() {
        resultsContainer.removeAllViews()
        resultsContainer.addView(ProgressBar(context).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            0f,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val m = (16 * d).toInt()
            setMargins(0, m, 0, m)
        })
        fadeInWrapper()
    }

    fun showError() {
        resultsContainer.removeAllViews()
        resultsContainer.addView(TextView(context).apply {
            text = "Search failed. Check your connection."
            setTextColor(Color.argb(200, 255, 100, 100))
            textSize = 16f
            gravity = Gravity.CENTER
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
        })
        fadeInWrapper()
    }

    fun clearResults() {
        resultsWrapper.fadeOut(duration = 100) { resultsContainer.removeAllViews() }
        refreshShortcuts(searchField)
    }

    // Initial shortcut population
    refreshShortcuts(searchField)

    return SearchOverlayResult(
        root          = root,
        showResults   = ::showResults,
        showLoading   = ::showLoading,
        showError     = ::showError,
        clearResults  = ::clearResults,
        // Refresh shortcuts so the panel is ready; keyboard opens only on field tap.
        prepareForOpen = { refreshShortcuts(searchField) },
        hideKeyboard   = { hideKeyboard(searchField) },
        isLocalSearch  = { localSearch },
        isGpsAnchor    = { gpsAnchor },
    )
}
