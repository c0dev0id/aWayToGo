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
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A single launcher shortcut row (maps to one [AppShortcutInfo] from the domain layer).
 * Kept in the UI layer to avoid pulling domain imports into view code.
 */
data class ShortcutRowInfo(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

/**
 * Data passed to the panel to populate an app row.
 *
 * [label] is the display name (custom if renamed, otherwise system label).
 * [originalLabel] is always the system-assigned label — used for rename Reset.
 */
data class AppRowInfo(
    val label: String,
    val originalLabel: String,
    val packageName: String,
    val icon: Drawable,
)

/**
 * Result of [buildAppsPanel].
 *
 * [root] is the panel FrameLayout added to the map root.
 * [appsButton] is the circle button at the bottom-right corner of the panel.
 * [appsALabel] is the bold "A" TextView shown when the panel is closed; it matches
 * the letter style of the SAT pill button and fades out as the panel opens.
 * [appsBars] are the three bar views that animate into a right arrow (→) when the
 * panel opens; they start hidden (scaleX=0) behind the "A" label.
 * [appListScroll] / [appListContainer] hold the main list of added apps.
 * [addAppRow] is the "Add App" action row at the bottom of the main list.
 * [addAppScroll] / [addAppContainer] hold the "Add App" submenu with checkboxes.
 * [appActionsScroll] / [appActionsContainer] hold the actions for a long-pressed app.
 */
class AppsPanelResult(
    val root: View,
    val appsButton: View,
    val appsALabel: TextView,
    val appsBars: List<View>,
    val appListScroll: ScrollView,
    val appListContainer: LinearLayout,
    val addAppRow: View,
    val addAppScroll: ScrollView,
    val addAppContainer: LinearLayout,
    val appActionsScroll: ScrollView,
    val appActionsContainer: LinearLayout,
)

/**
 * Builds the apps launcher panel.
 *
 * Layout (outer FrameLayout, gravity BOTTOM|END in parent):
 *   ├── appListScroll   (main list of added apps + "Add App" row)
 *   ├── addAppScroll    (submenu: all apps with checkboxes, initially GONE)
 *   ├── appActionsScroll (submenu: actions for a long-pressed app, initially GONE)
 *   └── appsButton      (64×64dp circle with A-icon bars, gravity BOTTOM|END — always visible)
 *
 * The button shows the letter "A" (bold white TextView, same style as the SAT pill button)
 * as its idle icon. Three hidden bars (scaleX=0) animate into a right arrow (→) as the
 * panel opens while the "A" label fades out.
 */
fun buildAppsPanel(context: Context, onAppsButton: () -> Unit): AppsPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d
    val panelW  = (280 * d).toInt()
    val btnSz   = (64 * d).toInt()

    // ── Arrow bars — same structure as the hamburger in MenuPanel ───────────────
    // Three bars pivot around the button's centre Y, identical to the main menu.
    // Initial state: scaleX=0 (hidden behind the "A" label). They grow into a
    // right arrow (→) at ±45°/0° with scaleX 0.5/1/0.5 when the panel opens.
    val barH     = (3 * d).toInt().coerceAtLeast(2)
    val barW     = (32 * d).toInt()
    val btnPad   = (12 * d).toInt()
    val contentH = btnSz - 2 * btnPad          // 40dp icon area
    val iconCY   = btnSz / 2f                   // 32dp — button centre Y
    val barLeftMargin = (iconCY - barW / 2f).toInt()

    // Bar Y centres at 1/4, 2/4, 3/4 of the icon area (same spacing as hamburger).
    val barTops = Array(3) { i ->
        val barCY = btnPad + contentH * (i + 1f) / 4f
        (barCY - barH / 2f).toInt()
    }

    val appsBars = Array(3) { i ->
        View(context).apply {
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = barH / 2f
                setColor(Color.WHITE)
            }
            pivotX = barW / 2f
            pivotY = iconCY - barTops[i]   // pivot at button centre Y
            scaleX = 0f                    // hidden; "A" label is shown instead
        }
    }

    // ── "A" label — matches the letter style of the SAT pill button ──────────
    val appsALabel = TextView(context).apply {
        text     = "A"
        setTextColor(Color.WHITE)
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        gravity  = Gravity.CENTER
    }

    val appsButton = FrameLayout(context).apply {
        clipChildren = false
        isClickable  = true
        isFocusable  = true
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(80, 255, 255, 255)),
            GradientDrawable().apply {
                shape        = GradientDrawable.OVAL
                setColor(Color.argb(220, 20, 20, 20))
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
        )
        setOnClickListener { onAppsButton() }
        appsBars.forEachIndexed { i, bar ->
            addView(bar, FrameLayout.LayoutParams(barW, barH).apply {
                gravity    = Gravity.TOP or Gravity.START
                topMargin  = barTops[i]
                leftMargin = barLeftMargin
            })
        }
        // "A" label sits on top of the bars; fills the button area so it stays centred.
        addView(appsALabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    // ── "Add App" action row ──────────────────────────────────────────────────
    val hPad = (16 * d).toInt()
    val addAppRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
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
            View(context).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
        )
        addView(
            TextView(context).apply {
                text = "Add App"
                setTextColor(Color.argb(180, 255, 255, 255))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(hPad, 0, hPad, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    // ── Scroll + container factory ─────────────────────────────────────────────
    fun scrollWithContainer(): Pair<ScrollView, LinearLayout> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            addView(container)
            isVerticalScrollBarEnabled = false
        }
        return scroll to container
    }

    val (appListScroll, appListContainer)       = scrollWithContainer()
    val (addAppScroll, addAppContainer)         = scrollWithContainer()
    val (appActionsScroll, appActionsContainer) = scrollWithContainer()

    // Submenus start hidden
    addAppScroll.visibility = View.GONE
    addAppScroll.alpha = 0f
    appActionsScroll.visibility = View.GONE
    appActionsScroll.alpha = 0f

    // Scroll views sit above the button (bottomMargin = btnSz).
    val scrollLp = FrameLayout.LayoutParams(
        panelW,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        bottomMargin = btnSz
    }

    // ── Root panel ────────────────────────────────────────────────────────────
    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        clipToOutline = true

        addView(appListScroll,    FrameLayout.LayoutParams(scrollLp))
        addView(addAppScroll,     FrameLayout.LayoutParams(scrollLp))
        addView(appActionsScroll, FrameLayout.LayoutParams(scrollLp))

        addView(appsButton, FrameLayout.LayoutParams(
            btnSz, btnSz,
            Gravity.BOTTOM or Gravity.END,
        ))
    }

    return AppsPanelResult(
        root                 = root,
        appsButton           = appsButton,
        appsALabel           = appsALabel,
        appsBars             = appsBars.toList(),
        appListScroll        = appListScroll,
        appListContainer     = appListContainer,
        addAppRow            = addAppRow,
        addAppScroll         = addAppScroll,
        addAppContainer      = addAppContainer,
        appActionsScroll     = appActionsScroll,
        appActionsContainer  = appActionsContainer,
    )
}

/**
 * Populate a list container with app rows.
 *
 * Long-pressing a row calls [onLongClick] with the row's [AppRowInfo].
 * Tapping a row calls [onClick] with the package name.
 *
 * @param container   The LinearLayout to populate (cleared first).
 * @param apps        List of app info to display.
 * @param actionRows  Optional action rows to append at the end (e.g. "Add App").
 * @param onClick     Called on tap (receives packageName).
 * @param onLongClick Called on long-press (receives full AppRowInfo). Null = no long-press.
 */
fun populateAppList(
    container: LinearLayout,
    apps: List<AppRowInfo>,
    actionRows: List<View> = emptyList(),
    onClick: (String) -> Unit,
    onLongClick: ((AppRowInfo) -> Unit)? = null,
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
            isLongClickable = onLongClick != null
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
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick(app); true }
            }
        }
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }

    for (actionRow in actionRows) {
        (actionRow.parent as? android.view.ViewGroup)?.removeView(actionRow)
        container.addView(actionRow, LinearLayout.LayoutParams(panelW, itemH))
    }
}

/**
 * Populate the "Add App" submenu with checkbox rows for all apps.
 *
 * @param container     The LinearLayout to populate (cleared first).
 * @param apps          All available apps.
 * @param addedPackages Set of package names currently added.
 * @param onToggle      Called when a checkbox is toggled (packageName, isChecked).
 */
fun populateAddAppList(
    container: LinearLayout,
    apps: List<AppRowInfo>,
    addedPackages: Set<String>,
    onToggle: (String, Boolean) -> Unit,
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

            val checkBox = CheckBox(context).apply {
                isChecked = addedPackages.contains(app.packageName)
                buttonTintList = ColorStateList.valueOf(Color.WHITE)
                setOnCheckedChangeListener { _, checked ->
                    onToggle(app.packageName, checked)
                }
            }
            addView(checkBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
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
            setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        }
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }
}

/**
 * Populate the app-actions submenu for a single app (reached by long-pressing in main list).
 *
 * The selected app's icon and name appear as a non-interactive header at the top,
 * followed by any launcher shortcuts (static + dynamic), then management actions:
 * Hide, Rename, App Info, Uninstall.
 *
 * @param container   The LinearLayout to populate (cleared first).
 * @param appIcon     Icon of the selected app.
 * @param appLabel    Display name of the selected app (custom name if renamed).
 * @param shortcuts   Launcher shortcuts to show above the management actions.
 * @param onShortcut  Called when a shortcut row is tapped.
 * @param onHide      Remove app from the launcher list.
 * @param onRename    Open the rename dialog.
 * @param onAppInfo   Open system app info screen.
 * @param onUninstall Open system uninstall dialog.
 */
fun populateAppActions(
    container: LinearLayout,
    appIcon: Drawable,
    appLabel: String,
    shortcuts: List<ShortcutRowInfo> = emptyList(),
    onShortcut: (ShortcutRowInfo) -> Unit = {},
    onHide: () -> Unit,
    onRename: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = container.context
    val d       = context.resources.displayMetrics.density
    val itemH   = (56 * d).toInt()
    val actionH = (48 * d).toInt()
    val iconSz  = (40 * d).toInt()
    val hPad    = (16 * d).toInt()
    val iconGap = (12 * d).toInt()
    val panelW  = (280 * d).toInt()

    container.removeAllViews()

    // ── App header (icon + name) — mirrors a main-list row, but not clickable ──
    container.addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
            addView(
                ImageView(context).apply {
                    setImageDrawable(appIcon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(iconSz, iconSz),
            )
            addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
            addView(
                TextView(context).apply {
                    text = appLabel
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    setSingleLine(true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        },
        LinearLayout.LayoutParams(panelW, itemH),
    )

    // Separator
    container.addView(
        View(context).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
    )

    // ── Launcher shortcuts ─────────────────────────────────────────────────────
    if (shortcuts.isNotEmpty()) {
        for (shortcut in shortcuts) {
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
                val iconView = ImageView(context).apply {
                    if (shortcut.icon != null) {
                        setImageDrawable(shortcut.icon)
                    } else {
                        // Fallback: a small white circle placeholder
                        setImageDrawable(GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.argb(80, 255, 255, 255))
                        })
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(iconView, LinearLayout.LayoutParams(iconSz, iconSz))
                addView(View(context), LinearLayout.LayoutParams(iconGap, 0))
                addView(
                    TextView(context).apply {
                        text = shortcut.label
                        setTextColor(Color.argb(220, 255, 255, 255))
                        textSize = 16f
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        setSingleLine(true)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                setOnClickListener { onShortcut(shortcut) }
            }
            container.addView(row, LinearLayout.LayoutParams(panelW, actionH))
        }

        // Separator before management actions
        container.addView(
            View(context).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
        )
    }

    // ── Action rows ───────────────────────────────────────────────────────────
    fun actionItem(label: String, onClick: () -> Unit) {
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
            setOnClickListener { onClick() }
        }
        container.addView(row, LinearLayout.LayoutParams(panelW, actionH))
    }

    actionItem("Hide",     onHide)
    actionItem("Rename",   onRename)
    actionItem("App Info", onAppInfo)
    actionItem("Uninstall", onUninstall)
}
