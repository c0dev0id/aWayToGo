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
 * [mainMenuScroll] is the ScrollView holding the 6 main-menu items; faded
 * during settings transitions.
 * [settingsRowInList] is the "Settings" item inside the main menu list;
 * its click is wired by MapActivity to enter the settings layer.
 * [settingsGhostHeader] is a full-width clone of the Settings row placed at
 * the panel's top (y=0), initially GONE.  During the enter-settings animation
 * it slides up from the list position and becomes the visible header.
 * [settingsContent] is the LinearLayout that holds settings items (Debug Mode
 * toggle, etc.), initially GONE.
 * [debugToggleLabel] is the TextView inside the Debug Mode item; its text is
 * kept in sync ("Debug Mode: OFF" / "Debug Mode: ON") by renderUiState.
 */
data class MenuPanelResult(
    val root: View,
    val hamburgerBars: Array<View>,
    val mainMenuScroll: ScrollView,
    val settingsRowInList: View,
    val settingsGhostHeader: View,
    val settingsContent: LinearLayout,
    val debugToggleLabel: TextView,
) {
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
 * Layout (outer FrameLayout → layers in z-order):
 *   ├── mainMenuScroll (ScrollView, topMargin=64dp)
 *   │     └── 6 menu items including Settings at the bottom
 *   ├── settingsContent (LinearLayout, topMargin=64dp, initially GONE)
 *   │     └── Debug Mode toggle
 *   ├── settingsGhostHeader (280×64dp row, gravity=TOP|START, initially GONE)
 *   └── hamburgerBtn (FrameLayout 64×64dp, gravity=TOP|START — on top of ghost)
 *
 * The ghost header starts with translationY set by MapActivity before the
 * enter-settings animation so it appears to rise from the list into the header.
 *
 * @param onToggleMenu  Called when the user taps the hamburger/back-arrow button.
 *                      MapActivity passes a lambda that checks [isInSettingsMenu]
 *                      and either exits the settings layer or toggles the menu.
 */
fun buildMenuPanel(context: Context, onToggleMenu: () -> Unit): MenuPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d
    val panelW  = (280 * d).toInt()
    val itemH   = (64 * d).toInt()
    val iconSz  = (28 * d).toInt()
    val hPad    = (16 * d).toInt()
    val iconGap = (12 * d).toInt()
    val btnPad  = (12 * d).toInt()

    // Single menu row: icon + label, full-width ripple.
    fun menuItem(iconRes: Int, label: String, clickable: Boolean = true): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isClickable = clickable
            isFocusable = clickable
            if (clickable) {
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                    null,
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.WHITE)
                    },
                )
            }
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

    // Three-bar hamburger icon: each bar rotates individually.
    val barH          = (3 * d).toInt().coerceAtLeast(2)
    val barW          = (32 * d).toInt()
    val contentH      = itemH - 2 * btnPad
    val iconCX        = itemH / 2f
    val iconCY        = itemH / 2f
    val barLeftMargin = (iconCX - barW / 2f).toInt()

    val hamburgerBars = Array(3) { i ->
        val barCY  = btnPad + contentH * (i + 1f) / 4f
        val barTop = (barCY - barH / 2f).toInt()
        View(context).apply {
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = barH / 2f
                setColor(Color.WHITE)
            }
            pivotX = barW / 2f
            pivotY = iconCY - barTop
        }
    }

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

    // ── Main menu items ────────────────────────────────────────────────────────
    val settingsRowInList = menuItem(R.drawable.ic_menu_settings, "Settings")

    val contentList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(menuItem(R.drawable.ic_menu_locations,    "My Locations"),  LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_trips,        "My Trips"),      LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_recordings,   "My Recordings"), LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_poi_groups,   "My POI Groups"), LinearLayout.LayoutParams(panelW, itemH))
        addView(menuItem(R.drawable.ic_menu_offline_maps, "Offline Maps"),  LinearLayout.LayoutParams(panelW, itemH))
        addView(settingsRowInList,                                           LinearLayout.LayoutParams(panelW, itemH))
    }

    val mainMenuScroll = ScrollView(context).apply { addView(contentList) }

    // ── Settings submenu content ───────────────────────────────────────────────
    val debugToggleLabel = TextView(context).apply {
        text = "Debug Mode: OFF"
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER_VERTICAL
    }

    val debugToggleItem = LinearLayout(context).apply {
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
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_menu_settings))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(iconSz, iconSz),
        )
        addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
        addView(
            debugToggleLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    val settingsContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility  = View.GONE
        alpha       = 0f
        addView(debugToggleItem, LinearLayout.LayoutParams(panelW, itemH))
    }

    // ── Ghost header — visually identical to settingsRowInList, not clickable ──
    // Positioned at the panel top (y=0..64dp).  MapActivity sets translationY
    // before the animation so it starts at the Settings item's list position.
    val settingsGhostHeader = menuItem(R.drawable.ic_menu_settings, "Settings", clickable = false).apply {
        visibility = View.GONE
        alpha      = 0f
    }

    // ── Root panel ────────────────────────────────────────────────────────────
    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        clipToOutline = true

        addView(mainMenuScroll, FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = itemH
        })

        addView(settingsContent, FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = itemH
        })

        // Ghost header added BEFORE hamburgerBtn so the button renders on top.
        addView(settingsGhostHeader, FrameLayout.LayoutParams(panelW, itemH).apply {
            gravity = Gravity.TOP or Gravity.START
        })

        addView(hamburgerBtn, FrameLayout.LayoutParams(itemH, itemH).apply {
            gravity = Gravity.TOP or Gravity.START
        })
    }

    return MenuPanelResult(
        root                = root,
        hamburgerBars       = hamburgerBars,
        mainMenuScroll      = mainMenuScroll,
        settingsRowInList   = settingsRowInList,
        settingsGhostHeader = settingsGhostHeader,
        settingsContent     = settingsContent,
        debugToggleLabel    = debugToggleLabel,
    )
}
