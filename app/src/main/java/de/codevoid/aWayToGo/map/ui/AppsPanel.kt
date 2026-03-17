package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Data passed to the panel to populate an app row.
 */
data class AppRowInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

/**
 * Result of [buildAppsPanel].
 *
 * [root] is the panel FrameLayout added to the map root.  It starts at the
 * APPS pill-button size and animates to full panel size when the menu opens.
 * [appsButton] is the "APPS" pill button at the bottom-right of the panel.
 * [appListScroll] is the ScrollView containing the visible app rows.
 * [appListContainer] is the LinearLayout inside the scroll, populated dynamically.
 * [hiddenHeaderRow] is the "Show Hidden" entry at the bottom of the main list.
 * [hiddenListScroll] is the ScrollView for hidden apps (initially GONE).
 * [hiddenListContainer] is the LinearLayout inside the hidden scroll.
 * [contextMenu] is the context menu overlay (initially GONE).
 * [contextHideLabel] is the "Hide"/"Show" toggle label inside the context menu.
 */
data class AppsPanelResult(
    val root: View,
    val appsButton: TextView,
    val appListScroll: ScrollView,
    val appListContainer: LinearLayout,
    val hiddenHeaderRow: View,
    val hiddenListScroll: ScrollView,
    val hiddenListContainer: LinearLayout,
    val contextMenu: LinearLayout,
    val contextHideLabel: TextView,
    val contextStopRow: View,
    val contextUninstallRow: View,
    val contextHideRow: View,
)

/**
 * Builds the apps launcher panel.
 *
 * Layout (outer FrameLayout, gravity BOTTOM|END in parent):
 *   ├── appListScroll (ScrollView, bottomMargin = buttonH)
 *   │     └── appListContainer (LinearLayout VERTICAL)
 *   │           └── [dynamically populated app rows + "Show Hidden" at bottom]
 *   ├── hiddenListScroll (ScrollView, bottomMargin = buttonH, initially GONE)
 *   │     └── hiddenListContainer (LinearLayout VERTICAL)
 *   │           └── [dynamically populated hidden app rows]
 *   ├── contextMenu (LinearLayout VERTICAL, initially GONE)
 *   │     ├── Hide/Show row
 *   │     ├── App Info row
 *   │     └── Uninstall row
 *   └── appsButton (APPS pill, gravity BOTTOM|END — always visible at corner)
 *
 * The panel's root is positioned at BOTTOM|END in the Activity root.
 * When closed, width/height match the pill button size.
 * When open, the panel grows leftward and upward (natural FrameLayout behaviour
 * with BOTTOM|END gravity in the parent).
 *
 * @param onToggleApps Called when the APPS button is tapped.
 */
fun buildAppsPanel(context: Context, onToggleApps: () -> Unit): AppsPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d
    val panelW  = (280 * d).toInt()
    val itemH   = (56 * d).toInt()
    val iconSz  = (40 * d).toInt()
    val hPad    = (16 * d).toInt()
    val iconGap = (12 * d).toInt()
    val buttonH = (48 * d).toInt()

    // ── App row factory ─────────────────────────────────────────────────────
    fun appRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                },
            )
            // icon placeholder
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconSz, iconSz),
            )
            addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
            // label placeholder
            addView(
                TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    // ── Context menu item factory ───────────────────────────────────────────
    fun contextItem(label: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                },
            )
            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    // ── "Show Hidden" row ───────────────────────────────────────────────────
    val hiddenHeaderRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(hPad, 0, hPad, 0)
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
            },
        )
        // Separator line at top
        addView(
            View(context).apply {
                setBackgroundColor(Color.argb(60, 255, 255, 255))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()).apply {
                bottomMargin = 0
            },
        )
        addView(
            TextView(context).apply {
                text = "Show Hidden"
                setTextColor(Color.argb(180, 255, 255, 255))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    // ── App list container ──────────────────────────────────────────────────
    val appListContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val appListScroll = ScrollView(context).apply {
        addView(appListContainer)
        isVerticalScrollBarEnabled = false
    }

    // ── Hidden list container ───────────────────────────────────────────────
    val hiddenListContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val hiddenListScroll = ScrollView(context).apply {
        addView(hiddenListContainer)
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        alpha = 0f
    }

    // ── Context menu ────────────────────────────────────────────────────────
    val contextHideLabel = TextView(context).apply {
        text = "Hide"
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
    }

    val contextHideRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(hPad, 0, hPad, 0)
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
            },
        )
        addView(
            contextHideLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    val contextStopRow   = contextItem("App Info")
    val contextUninstallRow = contextItem("Uninstall")

    val contextItemH = (48 * d).toInt()

    val contextMenu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility  = View.GONE
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 16 * d
            setColor(Color.argb(240, 30, 30, 30))
        }
        elevation = 8 * d
        addView(contextHideRow,      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, contextItemH))
        addView(contextStopRow,      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, contextItemH))
        addView(contextUninstallRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, contextItemH))
    }

    // ── APPS pill button ────────────────────────────────────────────────────
    val appsButton = makePillButton(context, "APPS") { onToggleApps() }

    // ── Root panel ──────────────────────────────────────────────────────────
    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        clipToOutline = true

        // App list scroll fills above the button
        addView(appListScroll, FrameLayout.LayoutParams(
            panelW,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = buttonH
        })

        // Hidden list scroll (same position, initially GONE)
        addView(hiddenListScroll, FrameLayout.LayoutParams(
            panelW,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = buttonH
        })

        // Context menu overlay (centered in panel)
        addView(contextMenu, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        // APPS button at bottom-right corner of the panel
        addView(appsButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ))
    }

    return AppsPanelResult(
        root               = root,
        appsButton         = appsButton,
        appListScroll      = appListScroll,
        appListContainer   = appListContainer,
        hiddenHeaderRow    = hiddenHeaderRow,
        hiddenListScroll   = hiddenListScroll,
        hiddenListContainer = hiddenListContainer,
        contextMenu        = contextMenu,
        contextHideLabel   = contextHideLabel,
        contextStopRow     = contextStopRow,
        contextUninstallRow = contextUninstallRow,
        contextHideRow     = contextHideRow,
    )
}

/**
 * Populate a list container with app rows.
 *
 * @param container The LinearLayout to populate (cleared first).
 * @param apps List of app info to display.
 * @param showHiddenRow Optional "Show Hidden" row to append at the end.
 * @param onClick Called when an app row is tapped (receives packageName).
 * @param onLongClick Called when an app row is long-pressed (receives packageName and the row view).
 */
fun populateAppList(
    container: LinearLayout,
    apps: List<AppRowInfo>,
    showHiddenRow: View? = null,
    onClick: (String) -> Unit,
    onLongClick: (String, View) -> Unit,
) {
    val context = container.context
    val d       = context.resources.displayMetrics.density
    val itemH   = (56 * d).toInt()
    val iconSz  = (40 * d).toInt()
    val hPad    = (16 * d).toInt()
    val iconGap = (12 * d).toInt()
    val panelW  = (280 * d).toInt()

    container.removeAllViews()

    for (app in apps) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                null,
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                },
            )
            addView(
                ImageView(context).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconSz, iconSz),
            )
            addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
            addView(
                TextView(context).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            setOnClickListener { onClick(app.packageName) }
            setOnLongClickListener { onLongClick(app.packageName, this); true }
        }
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }

    if (showHiddenRow != null) {
        // Remove from previous parent if re-adding
        (showHiddenRow.parent as? android.view.ViewGroup)?.removeView(showHiddenRow)
        container.addView(showHiddenRow, LinearLayout.LayoutParams(panelW, itemH))
    }
}
