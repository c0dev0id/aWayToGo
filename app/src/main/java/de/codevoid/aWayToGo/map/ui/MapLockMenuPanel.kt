package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import de.codevoid.aWayToGo.R

/**
 * Result of [buildMapLockMenuPanel].
 *
 * [root] is the panel FrameLayout added to the map root, centered on screen.
 * It starts at ring-diameter size and is expanded by the open animation.
 *
 * Each row view ([copyCoordinatesRow], [placeDragLineRow], [navigateRow],
 * [quickSearchRow]) has its click listener wired by [MapActivity].
 */
data class MapLockMenuPanelResult(
    val root: FrameLayout,
    val copyCoordinatesRow: View,
    val placeDragLineRow: View,
    val navigateRow: View,
    val quickSearchRow: View,
)

/**
 * Builds the map-lock popup menu: a dark rounded panel with four action rows,
 * using the same visual style as the main hamburger menu.
 *
 * Layout (FrameLayout root):
 *   └── LinearLayout (vertical)
 *         ├── Copy Coordinates
 *         ├── Place Drag Line
 *         ├── Navigate  (stub)
 *         └── Quick Search (stub)
 *
 * The panel starts collapsed at ring-diameter size ([RING_DIAMETER_DP] × [RING_DIAMETER_DP])
 * and is expanded to full width × item height × 4 by [MapActivity.runOpenMapLockMenuAnimation].
 */
fun buildMapLockMenuPanel(context: Context): MapLockMenuPanelResult {
    val d      = context.resources.displayMetrics.density
    val radius = 32 * d
    val panelW = (280 * d).toInt()
    val itemH  = (64 * d).toInt()
    val iconSz = (28 * d).toInt()
    val hPad   = (16 * d).toInt()
    val iconGap = (12 * d).toInt()

    fun menuItem(iconRes: Int, label: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isClickable = true
            isFocusable = true
            background  = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                },
            )
            addView(
                ImageView(context).apply {
                    setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconSz, iconSz),
            )
            addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity  = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    val copyCoordinatesRow = menuItem(R.drawable.ic_lock_copy_coords, "Copy Coordinates")
    val placeDragLineRow   = menuItem(R.drawable.ic_lock_drag_line,   "Place Drag Line")
    val navigateRow        = menuItem(R.drawable.ic_lock_navigate,    "Navigate")
    val quickSearchRow     = menuItem(R.drawable.ic_search,           "Quick Search")

    val contentList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(copyCoordinatesRow, LinearLayout.LayoutParams(panelW, itemH))
        addView(placeDragLineRow,   LinearLayout.LayoutParams(panelW, itemH))
        addView(navigateRow,        LinearLayout.LayoutParams(panelW, itemH))
        addView(quickSearchRow,     LinearLayout.LayoutParams(panelW, itemH))
    }

    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        clipToOutline = true
        // Start fully transparent; runOpenMapLockMenuAnimation fades + scales it in.
        alpha      = 0f
        visibility = View.GONE
        addView(
            contentList,
            FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    return MapLockMenuPanelResult(
        root               = root,
        copyCoordinatesRow = copyCoordinatesRow,
        placeDragLineRow   = placeDragLineRow,
        navigateRow        = navigateRow,
        quickSearchRow     = quickSearchRow,
    )
}
