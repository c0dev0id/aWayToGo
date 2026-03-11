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
 * @property root       The overlay view to add to the root layout.
 * @property showResults  Replace the result list with [results].
 * @property showLoading  Replace the result list with a spinner.
 * @property showError    Replace the result list with an error message.
 * @property clearResults Remove all results (back to the shortcuts-only state).
 */
class SearchOverlayResult(
    val root: FrameLayout,
    val showResults: (List<SearchResult>) -> Unit,
    val showLoading: () -> Unit,
    val showError: () -> Unit,
    val clearResults: () -> Unit,
)

/**
 * Build the search overlay panel.
 *
 * Layout (bottom-anchored, full-width):
 *
 *   ┌────────────────────────────────────────┐
 *   │  [Search field________________] [Go] ✕ │  input row
 *   │  ────────────────────────────────────  │
 *   │  Recent: [Term] [Loc] [Term2] …        │  shortcuts (HorizontalScrollView)
 *   │  ────────────────────────────────────  │
 *   │  [Result name]                         │  results list (ScrollView)
 *   │  [Result name]                         │
 *   └────────────────────────────────────────┘
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

    // ── Root panel ────────────────────────────────────────────────────────────
    val topCornerRadius = 32 * d
    val panelBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(topCornerRadius, topCornerRadius, topCornerRadius, topCornerRadius, 0f, 0f, 0f, 0f)
        setColor(Color.argb(220, 0, 0, 0))
    }

    // ── Input row ─────────────────────────────────────────────────────────────
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

    // ── Shortcuts row ─────────────────────────────────────────────────────────
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

        // Recent locations first (with 📍 prefix), then recent search terms
        recentSearches.getLocations().forEach { loc ->
            val card = makeShortcutCard("📍 ${loc.displayName.substringBefore(',')}") {
                field.setText(loc.displayName.substringBefore(','))
                onSearch(loc.displayName.substringBefore(','))
            }
            shortcutsContainer.addView(card, LinearLayout.LayoutParams(lp))
        }
        recentSearches.getSearchTerms().forEach { term ->
            val card = makeShortcutCard("🔍 $term") {
                field.setText(term)
                onSearch(term)
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

    // ── Results area ──────────────────────────────────────────────────────────
    val resultsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val resultsScroll = ScrollView(context).apply {
        val maxH = (300 * d).toInt()
        // Constrain the scroll area height so it doesn't push content off screen
        minimumHeight = 0
        addView(resultsContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        // Apply max height via layout weight / constraints via parent
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxH,
        )
    }

    // ── Divider helper ────────────────────────────────────────────────────────
    fun makeDivider(): View = View(context).apply {
        setBackgroundColor(Color.argb(60, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (1 * d).toInt(),
        )
    }

    // ── Main panel (vertical) ─────────────────────────────────────────────────
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = panelBg
        addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(makeDivider())
        addView(shortcutsSection, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        addView(makeDivider())
        addView(resultsScroll)
    }

    val root = FrameLayout(context).apply {
        addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    // ── Wire up search actions ────────────────────────────────────────────────
    fun triggerSearch() {
        val query = searchField.text.toString().trim()
        if (query.isNotEmpty()) onSearch(query)
    }

    goButton.setOnClickListener { triggerSearch() }
    searchField.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH) { triggerSearch(); true } else false
    }

    // ── State mutators returned to MapActivity ────────────────────────────────
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
            return
        }
        results.forEachIndexed { i, result ->
            if (i > 0) resultsContainer.addView(makeDivider())
            resultsContainer.addView(makeResultRow(result) { onResultClick(result) })
        }
        // Refresh shortcuts now (recent terms were saved before this call)
        refreshShortcuts(searchField)
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
    }

    fun clearResults() {
        resultsContainer.removeAllViews()
        refreshShortcuts(searchField)
    }

    // Initial shortcut population
    refreshShortcuts(searchField)

    return SearchOverlayResult(
        root         = root,
        showResults  = ::showResults,
        showLoading  = ::showLoading,
        showError    = ::showError,
        clearResults = ::clearResults,
    )
}
