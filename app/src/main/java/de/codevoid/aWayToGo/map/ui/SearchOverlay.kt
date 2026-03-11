package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
 * @property root                 The overlay view to add to the root layout.
 * @property showResults          Replace the result list with [results].
 * @property showLoading          Replace the result list with a spinner.
 * @property showError            Replace the result list with an error message.
 * @property clearResults         Remove all results and hide the result panel.
 * @property focusAndShowKeyboard Focus the search field and open the soft keyboard.
 *                                Also refreshes the shortcuts row with the latest data.
 * @property hideKeyboard         Dismiss the soft keyboard.
 */
class SearchOverlayResult(
    val root: FrameLayout,
    val showResults: (List<SearchResult>) -> Unit,
    val showLoading: () -> Unit,
    val showError: () -> Unit,
    val clearResults: () -> Unit,
    val focusAndShowKeyboard: () -> Unit,
    val hideKeyboard: () -> Unit,
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
 * @param context         Activity context.
 * @param recentSearches  Storage for recent queries and visited locations.
 * @param onClose         Called when the ✕ button is tapped.
 * @param onSearch        Called with the query string when Go is tapped / IME action fired.
 * @param onResultClick   Called when the user taps a result row.
 */
fun buildSearchOverlay(
    context: Context,
    recentSearches: RecentSearches,
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

    // ── Helper: result row ────────────────────────────────────────────────────
    fun makeResultRow(result: SearchResult, onClick: () -> Unit): TextView {
        val hPad = (16 * d).toInt()
        val vPad = (12 * d).toInt()
        // Split at first comma for a 2-line effect: bold primary name, smaller detail
        val comma = result.displayName.indexOf(',')
        val primary = if (comma > 0) result.displayName.substring(0, comma) else result.displayName
        val detail  = if (comma > 0) result.displayName.substring(comma + 1).trim() else ""
        val span = android.text.SpannableStringBuilder().apply {
            append(primary, android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (detail.isNotEmpty()) {
                append("\n")
                val start = length
                append(detail)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), start, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(Color.argb(180, 255, 255, 255)),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return TextView(context).apply {
            text = span
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(hPad, vPad, hPad, vPad)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply { setColor(Color.WHITE) },
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ── Root panel background ─────────────────────────────────────────────────
    val topCornerRadius = 32 * d
    val panelBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(topCornerRadius, topCornerRadius, topCornerRadius, topCornerRadius, 0f, 0f, 0f, 0f)
        setColor(Color.argb(220, 0, 0, 0))
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

    // Wrapper holds the scroll + a divider below it. Starts hidden.
    val resultsWrapper = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (250 * d).toInt(),
        ))
        addView(makeDivider())
    }

    // ── Main panel: results (top) → shortcuts → divider → input (bottom) ──────
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBg
        addView(resultsWrapper, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(shortcutsSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(makeDivider())
        addView(inputRow, LinearLayout.LayoutParams(
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

    goButton.setOnClickListener { triggerSearch() }
    searchField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) { triggerSearch(); true } else false
    }

    // ── State mutators returned to MapActivity ────────────────────────────────
    fun showResults(results: List<SearchResult>) {
        resultsContainer.removeAllViews()
        resultsWrapper.visibility = View.VISIBLE
        if (results.isEmpty()) {
            resultsContainer.addView(TextView(context).apply {
                text = "No results found."
                setTextColor(Color.argb(180, 255, 255, 255))
                textSize = 16f
                gravity = Gravity.CENTER
                val p = (16 * d).toInt()
                setPadding(p, p, p, p)
            })
            return
        }
        results.forEachIndexed { i, result ->
            if (i > 0) resultsContainer.addView(makeDivider())
            resultsContainer.addView(makeResultRow(result) { onResultClick(result) })
        }
        // Refresh shortcuts now that the new search term has been saved.
        refreshShortcuts(searchField)
    }

    fun showLoading() {
        resultsContainer.removeAllViews()
        resultsWrapper.visibility = View.VISIBLE
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
    }

    fun showError() {
        resultsContainer.removeAllViews()
        resultsWrapper.visibility = View.VISIBLE
        resultsContainer.addView(TextView(context).apply {
            text = "Search failed. Check your connection."
            setTextColor(Color.argb(200, 255, 100, 100))
            textSize = 16f
            gravity = Gravity.CENTER
            val p = (16 * d).toInt()
            setPadding(p, p, p, p)
        })
    }

    fun clearResults() {
        resultsContainer.removeAllViews()
        resultsWrapper.visibility = View.GONE
        refreshShortcuts(searchField)
    }

    // ── Keyboard helpers ──────────────────────────────────────────────────────
    val focusAndShowKeyboard: () -> Unit = {
        refreshShortcuts(searchField)
        searchField.requestFocus()
        // post() defers until the view is laid out and attached, which is required
        // for showSoftInput to succeed when the overlay was just made VISIBLE.
        searchField.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    val hideKeyboard: () -> Unit = {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // Initial shortcut population
    refreshShortcuts(searchField)

    return SearchOverlayResult(
        root                 = root,
        showResults          = ::showResults,
        showLoading          = ::showLoading,
        showError            = ::showError,
        clearResults         = ::clearResults,
        focusAndShowKeyboard = focusAndShowKeyboard,
        hideKeyboard         = hideKeyboard,
    )
}
