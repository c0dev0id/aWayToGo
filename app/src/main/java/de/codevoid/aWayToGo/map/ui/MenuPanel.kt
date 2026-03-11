package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import de.codevoid.aWayToGo.R

/**
 * Result of [buildMenuPanel].
 *
 * [root] is the panel view added to the map root.
 * [hamburgerBars] are the three bar views that make up the hamburger icon;
 * stored separately so [MapActivity.runOpenMenuAnimation] and
 * [MapActivity.runCloseMenuAnimation] can rotate them individually.
 */
data class MenuPanelResult(
    val root: View,
    val hamburgerBars: Array<View>,
) {
    // Suppress warning: Array equality is identity-based, but the array is
    // only used for direct element access, never for equality comparisons.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MenuPanelResult) return false
        return root == other.root && hamburgerBars.contentEquals(other.hamburgerBars)
    }
    override fun hashCode(): Int = 31 * root.hashCode() + hamburgerBars.contentHashCode()
}

/**
 * Popup menu panel that doubles as the hamburger button.
 *
 * Design: at rest the panel is 64×64dp with cornerRadius=32dp — identical
 * to a circle.  [MapActivity.runOpenMenuAnimation] and [runCloseMenuAnimation]
 * animate the LayoutParams width+height from button size to full panel size
 * and back, while [hamburgerBars] rotate 90° in a staggered cascade.
 *
 * Structure (outer FrameLayout → inner LinearLayout wrapper):
 *   ├── hamburgerRow (64dp, fixed above scroll — always the topmost element)
 *   └── ScrollView
 *         └── 6 menu items
 *
 * The visibility is managed by [MapActivity.renderUiState]: VISIBLE in
 * EXPLORE mode, GONE otherwise.
 *
 * @param onToggleMenu  Called when the user taps the hamburger button.
 */
fun buildMenuPanel(context: Context, onToggleMenu: () -> Unit): MenuPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d         // cornerRadius=32dp → circle at 64dp, rounded-rect when expanded
    val panelW  = (280 * d).toInt()
    val itemH   = (64 * d).toInt()
    val iconSz  = (28 * d).toInt()
    val hPad    = (16 * d).toInt()
    val iconGap = (12 * d).toInt()
    val btnPad  = (12 * d).toInt()

    // Single menu row: icon + label, full-width ripple.
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
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    // Three-bar hamburger icon: each bar is a separate View so they can be rotated
    // individually during open/close.  All pivots point to the icon centre (32dp, 32dp)
    // so they rotate as if they were one unit — but at different speeds.
    //
    // Layout inside the 64×64dp button area (btnPad=12dp, contentArea=40×40dp):
    //   bar 0 centre at ¼ of content height = 22dp from top
    //   bar 1 centre at ½ of content height = 32dp from top  (= icon centre)
    //   bar 2 centre at ¾ of content height = 42dp from top
    //
    // pivotY for each bar = iconCY − barTop, so the rotation axis is always the
    // icon centre (32dp) in parent coordinates.
    // pivotX = barW/2 because the bar is centred on iconCX, so bar.left = iconCX − barW/2
    // and bar.left + pivotX = iconCX − barW/2 + barW/2 = iconCX.
    val barH          = (3 * d).toInt().coerceAtLeast(2)
    val barW          = (32 * d).toInt()                     // 32dp, centred on icon
    val contentH      = itemH - 2 * btnPad                   // 40dp
    val iconCX        = itemH / 2f                           // 32dp in px
    val iconCY        = itemH / 2f
    val barLeftMargin = (iconCX - barW / 2f).toInt()         // 16dp — centres bar on icon

    val hamburgerBars = Array(3) { i ->
        val barCY  = btnPad + contentH * (i + 1f) / 4f   // 22, 32, 42 dp
        val barTop = (barCY - barH / 2f).toInt()
        View(context).apply {
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = barH / 2f
                setColor(Color.WHITE)
            }
            pivotX = barW / 2f                // centre of bar = iconCX in parent coords
            pivotY = iconCY - barTop          // distance from bar's top to icon centre Y
        }
    }

    // Hamburger button container — clipChildren=false lets the bars draw outside their
    // own 32×3dp layout rectangles during rotation without being clipped.
    val hamburgerBtn = FrameLayout(context).apply {
        clipChildren = false
        isClickable  = true
        isFocusable  = true
        setOnClickListener { onToggleMenu() }
        hamburgerBars.forEachIndexed { i, bar ->
            val barCY  = btnPad + contentH * (i + 1f) / 4f
            val barTop = (barCY - barH / 2f).toInt()
            addView(bar, FrameLayout.LayoutParams(barW, barH).apply {
                gravity    = Gravity.TOP or Gravity.START
                topMargin  = barTop
                leftMargin = barLeftMargin
            })
        }
    }

    // 6 menu items, scrollable.
    val contentList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(menuItem(R.drawable.ic_menu_locations,    "My Locations"),  LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_trips,        "My Trips"),      LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_recordings,   "My Recordings"), LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_poi_groups,   "My POI Groups"), LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_offline_maps, "Offline Maps"),  LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_settings,     "Settings"),      LinearLayout.LayoutParams(panelW, itemH))
    }

    val scroll = ScrollView(context).apply { addView(contentList) }

    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        // Clip content to the rounded-rect outline so children don't bleed
        // through corners as the panel grows beyond the button-sized initial rect.
        clipToOutline = true

        // Scroll content pushed below the 64dp header area.
        addView(scroll, FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = itemH
        })

        addView(hamburgerBtn, FrameLayout.LayoutParams(itemH, itemH).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        // Starts VISIBLE at button size (64×64dp set by addView LayoutParams in onCreate).
        // renderUiState() manages VISIBLE/GONE.
    }

    return MenuPanelResult(root = root, hamburgerBars = hamburgerBars)
}
