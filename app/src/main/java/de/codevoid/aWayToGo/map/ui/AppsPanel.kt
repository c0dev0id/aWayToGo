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
 * [root] is the panel FrameLayout added to the map root.
 * [appsButton] is the "APPS" pill button at the bottom-right of the panel.
 * [appListScroll] / [appListContainer] hold the main list of added apps.
 * [addAppRow] is the "Add App" action row at the bottom of the main list.
 * [manageRow] is the "Manage" action row at the bottom of the main list.
 * [addAppScroll] / [addAppContainer] hold the "Add App" submenu with checkboxes.
 * [manageScroll] / [manageContainer] hold the "Manage" submenu.
 * [manageActionsScroll] / [manageActionsContainer] hold actions for a selected app.
 */
data class AppsPanelResult(
    val root: View,
    val appsButton: TextView,
    val appListScroll: ScrollView,
    val appListContainer: LinearLayout,
    val addAppRow: View,
    val manageRow: View,
    val addAppScroll: ScrollView,
    val addAppContainer: LinearLayout,
    val manageScroll: ScrollView,
    val manageContainer: LinearLayout,
    val manageActionsScroll: ScrollView,
    val manageActionsContainer: LinearLayout,
)

/**
 * Builds the apps launcher panel.
 *
 * Layout (outer FrameLayout, gravity BOTTOM|END in parent):
 *   ├── appListScroll (main list of added apps + action rows)
 *   ├── addAppScroll  (submenu: all apps with checkboxes, initially GONE)
 *   ├── manageScroll  (submenu: added apps for management, initially GONE)
 *   ├── manageActionsScroll (submenu: actions for selected app, initially GONE)
 *   └── appsButton    (APPS pill, gravity BOTTOM|END — always visible at corner)
 */
fun buildAppsPanel(context: Context, onToggleApps: () -> Unit): AppsPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d
    val panelW  = (280 * d).toInt()
    val buttonH = (48 * d).toInt()

    // ── Action row factory (Add App / Manage) ────────────────────────────
    fun actionRow(label: String): LinearLayout {
        val hPad = (16 * d).toInt()
        return LinearLayout(context).apply {
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
            // Separator line at top
            addView(
                View(context).apply {
                    setBackgroundColor(Color.argb(60, 255, 255, 255))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
            )
            addView(
                TextView(context).apply {
                    text = label
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
    }

    val addAppRow  = actionRow("Add App")
    val manageRow  = actionRow("Manage")

    // ── Scroll + container factory ───────────────────────────────────────
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

    val (appListScroll, appListContainer)             = scrollWithContainer()
    val (addAppScroll, addAppContainer)               = scrollWithContainer()
    val (manageScroll, manageContainer)               = scrollWithContainer()
    val (manageActionsScroll, manageActionsContainer) = scrollWithContainer()

    // Submenus start hidden
    addAppScroll.visibility = View.GONE
    addAppScroll.alpha = 0f
    manageScroll.visibility = View.GONE
    manageScroll.alpha = 0f
    manageActionsScroll.visibility = View.GONE
    manageActionsScroll.alpha = 0f

    // ── APPS pill button ────────────────────────────────────────────────
    val appsButton = makePillButton(context, "APPS") { onToggleApps() }

    // ── Root panel ──────────────────────────────────────────────────────
    val scrollLp = FrameLayout.LayoutParams(
        panelW,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        bottomMargin = buttonH
    }

    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.argb(220, 20, 20, 20))
        }
        clipToOutline = true

        addView(appListScroll, FrameLayout.LayoutParams(scrollLp))
        addView(addAppScroll, FrameLayout.LayoutParams(scrollLp))
        addView(manageScroll, FrameLayout.LayoutParams(scrollLp))
        addView(manageActionsScroll, FrameLayout.LayoutParams(scrollLp))

        addView(appsButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ))
    }

    return AppsPanelResult(
        root                   = root,
        appsButton             = appsButton,
        appListScroll          = appListScroll,
        appListContainer       = appListContainer,
        addAppRow              = addAppRow,
        manageRow              = manageRow,
        addAppScroll           = addAppScroll,
        addAppContainer        = addAppContainer,
        manageScroll           = manageScroll,
        manageContainer        = manageContainer,
        manageActionsScroll    = manageActionsScroll,
        manageActionsContainer = manageActionsContainer,
    )
}

/**
 * Populate a list container with app rows (for main list and manage submenu).
 *
 * @param container The LinearLayout to populate (cleared first).
 * @param apps List of app info to display.
 * @param actionRows Optional action rows to append at the end.
 * @param onClick Called when an app row is tapped (receives packageName).
 */
fun populateAppList(
    container: LinearLayout,
    apps: List<AppRowInfo>,
    actionRows: List<View> = emptyList(),
    onClick: (String) -> Unit,
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
 * @param container The LinearLayout to populate (cleared first).
 * @param apps All available apps.
 * @param addedPackages Set of package names currently added.
 * @param onToggle Called when a checkbox is toggled (packageName, isChecked).
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
            setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }
        }
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }
}

/**
 * Populate the manage-actions submenu for a single app.
 *
 * @param container The LinearLayout to populate (cleared first).
 * @param appLabel The label of the selected app (shown as header).
 * @param onRemove Called to remove the app from the added list.
 * @param onAppInfo Called to open system app info.
 * @param onUninstall Called to uninstall the app.
 */
fun populateManageActions(
    container: LinearLayout,
    appLabel: String,
    onRemove: () -> Unit,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = container.context
    val d       = context.resources.displayMetrics.density
    val itemH   = (48 * d).toInt()
    val hPad    = (16 * d).toInt()
    val panelW  = (280 * d).toInt()

    container.removeAllViews()

    // Header with app name
    container.addView(
        TextView(context).apply {
            text = appLabel
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, 0, hPad, 0)
        },
        LinearLayout.LayoutParams(panelW, itemH),
    )

    // Separator
    container.addView(
        View(context).apply {
            setBackgroundColor(Color.argb(60, 255, 255, 255))
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()),
    )

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
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }

    actionItem("Remove", onRemove)
    actionItem("App Info", onAppInfo)
    actionItem("Uninstall", onUninstall)
}
