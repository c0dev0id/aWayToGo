package de.codevoid.aWayToGo.map.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
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
 * [appsBars] are the three bar views that form the letter "A" in the idle state and
 * animate into a right arrow (→) when the panel opens.
 * [appListScroll] / [appListContainer] hold the main list of added apps.
 * [addAppRow] is the "Add App" action row at the bottom of the main list.
 * [addAppScroll] / [addAppContainer] hold the "Add App" submenu with checkboxes.
 * [appActionsScroll] / [appActionsContainer] hold the actions for a long-pressed app.
 * [appListHeader] is a fixed "Apps" title row at the top of the panel, shown when open.
 * [addAppGhostHeader] is a ghost header for the "Add App" submenu transition.
 * [appActionsGhostHeader] is a ghost header for the app-actions submenu transition;
 * its [appActionsGhostIcon] and [appActionsGhostLabel] are populated before animating.
 */
class AppsPanelResult(
    val root: View,
    val appsButton: View,
    val appsBars: List<View>,
    val appListScroll: ScrollView,
    val appListContainer: LinearLayout,
    val addAppRow: View,
    val addAppScroll: ScrollView,
    val addAppContainer: LinearLayout,
    val appActionsScroll: ScrollView,
    val appActionsContainer: LinearLayout,
    val appListHeader: View,
    val addAppGhostHeader: View,
    val appActionsGhostHeader: LinearLayout,
    val appActionsGhostIcon: ImageView,
    val appActionsGhostLabel: TextView,
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
 * The button shows the letter "A" drawn by three bars in their idle/closed state:
 *   Bar 0 at −60°: left diagonal leg "/"
 *   Bar 1 at   0°: horizontal crossbar "─"
 *   Bar 2 at +60°: right diagonal leg "\"
 * When the panel opens the bars animate into a right arrow (→).
 */
fun buildAppsPanel(context: Context, onAppsButton: () -> Unit): AppsPanelResult {
    val d       = context.resources.displayMetrics.density
    val radius  = 32 * d
    val panelW  = (280 * d).toInt()
    val btnSz   = (64 * d).toInt()
    val headerH = (56 * d).toInt()   // matches app-row height
    val iconSz  = (40 * d).toInt()
    val iconGap = (12 * d).toInt()

    // ── "A" bars ──────────────────────────────────────────────────────────────────
    // Idle state: bars form the letter "A".
    // All three bars pivot around the button centre (iconCY × iconCY).
    //
    // Bars 0 and 2 (the legs) sit at the same 1/4-height mark and are offset
    // ±1dp from the button centre X.  After rotating ∓60° around the button
    // centre their inner tips converge at a shared apex at the top-centre of
    // the icon area, forming a proper "A":
    //
    //   Bar 0 (left  leg "/"): leftMargin = centre+1dp,  rot = −60°
    //   Bar 1 (crossbar  "─"): leftMargin = centre,      rot =   0°
    //   Bar 2 (right leg "\"): leftMargin = centre−1dp,  rot = +60°
    //
    // When the panel opens the bars animate into a right arrow (→).
    val barH     = (3 * d).toInt().coerceAtLeast(2)
    val barW     = (32 * d).toInt()
    val btnPad   = (12 * d).toInt()
    val contentH = btnSz - 2 * btnPad          // 40dp icon area
    val iconCY   = btnSz / 2f                   // 32dp — button centre (square button, same for X and Y)

    // Leg bars are at 1/4 height; crossbar at 2/4 (centre).
    val legTop  = (btnPad + contentH * 1f / 4f - barH / 2f).toInt()
    val xbarTop = (btnPad + contentH * 2f / 4f - barH / 2f).toInt()

    val barTopsArr    = intArrayOf(legTop, xbarTop, legTop)
    val barMarginsArr = intArrayOf(
        (iconCY - barW / 2f + d).toInt(),   // bar 0: 1dp right of centre
        (iconCY - barW / 2f).toInt(),        // bar 1: centred
        (iconCY - barW / 2f - d).toInt(),   // bar 2: 1dp left of centre
    )

    // Initial rotations for the "A" formation.
    val aRotations = floatArrayOf(-60f, 0f, +60f)

    val appsBars = Array(3) { i ->
        View(context).apply {
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = barH / 2f
                setColor(Color.WHITE)
            }
            // pivotX/Y in view-local coords so every bar pivots around the button centre.
            pivotX   = iconCY - barMarginsArr[i]
            pivotY   = iconCY - barTopsArr[i]
            rotation = aRotations[i]
        }
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
                topMargin  = barTopsArr[i]
                leftMargin = barMarginsArr[i]
            })
        }
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

    // Scroll views sit above the button; topMargin reserves space for the header at the top.
    val scrollLp = FrameLayout.LayoutParams(
        panelW,
        FrameLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity      = Gravity.BOTTOM or Gravity.END
        bottomMargin = btnSz
        topMargin    = headerH
    }

    // ── Header views (fixed at panel top, one visible at a time) ──────────────

    // "Apps" title — visible when the main app list is shown.
    val appListHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(hPad, 0, hPad, 0)
        visibility  = View.GONE
        alpha       = 0f
        addView(
            TextView(context).apply {
                text = "Apps"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    // Ghost header for the "Add App" submenu transition.
    val addAppGhostHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(hPad, 0, hPad, 0)
        visibility  = View.GONE
        alpha       = 0f
        addView(
            TextView(context).apply {
                text = "Add App"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity  = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    // Ghost header for the app-actions submenu — icon+label populated before animating.
    val appActionsGhostIcon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val appActionsGhostLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity  = Gravity.CENTER_VERTICAL
        maxLines = 1
        setSingleLine(true)
    }
    val appActionsGhostHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        setPadding(hPad, 0, hPad, 0)
        visibility  = View.GONE
        alpha       = 0f
        addView(appActionsGhostIcon,  LinearLayout.LayoutParams(iconSz, iconSz))
        addView(View(context),        LinearLayout.LayoutParams(iconGap, 0))
        addView(appActionsGhostLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    // ── Root panel ────────────────────────────────────────────────────────────
    val headerLp = FrameLayout.LayoutParams(panelW, headerH).apply {
        gravity = Gravity.TOP or Gravity.END
    }

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

        // Ghost headers sit at the top of the panel, below the button in z-order.
        addView(appListHeader,         FrameLayout.LayoutParams(headerLp))
        addView(addAppGhostHeader,     FrameLayout.LayoutParams(headerLp))
        addView(appActionsGhostHeader, FrameLayout.LayoutParams(headerLp))

        addView(appsButton, FrameLayout.LayoutParams(
            btnSz, btnSz,
            Gravity.BOTTOM or Gravity.END,
        ))
    }

    return AppsPanelResult(
        root                     = root,
        appsButton               = appsButton,
        appsBars                 = appsBars.toList(),
        appListScroll            = appListScroll,
        appListContainer         = appListContainer,
        addAppRow                = addAppRow,
        addAppScroll             = addAppScroll,
        addAppContainer          = addAppContainer,
        appActionsScroll         = appActionsScroll,
        appActionsContainer      = appActionsContainer,
        appListHeader            = appListHeader,
        addAppGhostHeader        = addAppGhostHeader,
        appActionsGhostHeader    = appActionsGhostHeader,
        appActionsGhostIcon      = appActionsGhostIcon,
        appActionsGhostLabel     = appActionsGhostLabel,
    )
}

/**
 * Populate a list container with app rows.
 *
 * Long-pressing a row calls [onLongClick] with the row's [AppRowInfo].
 * Tapping a row calls [onClick] with the package name.
 * If [onReorder] is provided, each row gets a drag handle on the right; dragging it
 * reorders the list and calls [onReorder] with the new package-name order on drop.
 *
 * @param container   The LinearLayout to populate (cleared first).
 * @param apps        List of app info to display.
 * @param actionRows  Optional action rows to append at the end (e.g. "Add App").
 * @param onClick     Called on tap (receives packageName).
 * @param onLongClick Called on long-press (receives full AppRowInfo). Null = no long-press.
 * @param onReorder   Called on successful drag-drop with the new ordered package list.
 */
fun populateAppList(
    container: LinearLayout,
    apps: List<AppRowInfo>,
    actionRows: List<View> = emptyList(),
    onClick: (String) -> Unit,
    onLongClick: ((AppRowInfo) -> Unit)? = null,
    onReorder: ((List<String>) -> Unit)? = null,
) {
    val context  = container.context
    val d        = context.resources.displayMetrics.density
    val itemH    = (56 * d).toInt()
    val iconSz   = (40 * d).toInt()
    val hPad     = (16 * d).toInt()
    val iconGap  = (12 * d).toInt()
    val handleW  = (36 * d).toInt()
    val panelW   = (280 * d).toInt()

    container.removeAllViews()

    // ── Shared drag state ──────────────────────────────────────────────────────
    // intArrayOf / arrayOfNulls are used so lambdas can mutate captured state.
    val rowViews    = mutableListOf<View>()
    val handles     = mutableListOf<View>()
    val currentOrder = apps.map { it.packageName }.toMutableList()
    val draggingIdx  = intArrayOf(-1)
    val targetIdx    = intArrayOf(-1)
    val ghostRef     = arrayOfNulls<ImageView>(1)

    // ── Build rows ─────────────────────────────────────────────────────────────
    for (app in apps) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            // No right padding when a handle is present — the handle provides the edge.
            setPadding(hPad, 0, if (onReorder != null) 0 else hPad, 0)
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

            if (onReorder != null) {
                // ── Drag handle — three-dot grip on the right ──────────────────
                val handle = TextView(context).apply {
                    text = "⠿"
                    textSize = 16f
                    setTextColor(Color.argb(100, 255, 255, 255))
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = false
                }
                handles.add(handle)
                addView(handle, LinearLayout.LayoutParams(handleW, itemH))
            }

            setOnClickListener { onClick(app.packageName) }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick(app); true }
            }
        }
        rowViews.add(row)
        container.addView(row, LinearLayout.LayoutParams(panelW, itemH))
    }

    // ── Wire drag listeners after all rows are built ───────────────────────────
    if (onReorder != null) {
        handles.forEachIndexed { hIdx, handle ->
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {

                    MotionEvent.ACTION_DOWN -> {
                        val scrollView = container.parent as? ScrollView
                            ?: return@setOnTouchListener false
                        val panelRoot = scrollView.parent as? FrameLayout
                            ?: return@setOnTouchListener false

                        draggingIdx[0] = hIdx
                        targetIdx[0]   = hIdx

                        // Prevent the ScrollView from stealing subsequent events.
                        scrollView.requestDisallowInterceptTouchEvent(true)

                        // Capture a bitmap of the row to use as the drag ghost.
                        val row = rowViews[hIdx]
                        val bmp = Bitmap.createBitmap(
                            row.width.coerceAtLeast(1),
                            row.height.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        row.draw(Canvas(bmp))

                        val panelLoc = IntArray(2)
                        panelRoot.getLocationOnScreen(panelLoc)

                        val ghost = ImageView(context).apply {
                            setImageBitmap(bmp)
                            alpha     = 0.88f
                            elevation = 8 * d
                            translationY = (event.rawY - panelLoc[1] - itemH / 2f)
                                .coerceIn(0f, (panelRoot.height - itemH).toFloat())
                        }
                        // Add at END gravity to match the scroll view's horizontal alignment.
                        panelRoot.addView(ghost, FrameLayout.LayoutParams(
                            panelW, itemH, Gravity.TOP or Gravity.END,
                        ))
                        ghostRef[0] = ghost

                        row.alpha = 0.2f
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val ghost = ghostRef[0] ?: return@setOnTouchListener true
                        val scrollView = container.parent as? ScrollView
                            ?: return@setOnTouchListener true
                        val panelRoot = scrollView.parent as? FrameLayout
                            ?: return@setOnTouchListener true

                        val panelLoc = IntArray(2)
                        panelRoot.getLocationOnScreen(panelLoc)
                        ghost.translationY = (event.rawY - panelLoc[1] - itemH / 2f)
                            .coerceIn(0f, (panelRoot.height - itemH).toFloat())

                        // Determine which slot the ghost is hovering over.
                        val containerLoc = IntArray(2)
                        container.getLocationOnScreen(containerLoc)
                        val yInContainer = event.rawY - containerLoc[1]
                        val newTarget = (yInContainer / itemH).toInt()
                            .coerceIn(0, rowViews.size - 1)

                        if (newTarget != targetIdx[0]) {
                            targetIdx[0] = newTarget
                            animateRowDisplacement(rowViews, draggingIdx[0], newTarget, itemH.toFloat())
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val ghost = ghostRef[0] ?: return@setOnTouchListener true
                        val scrollView = container.parent as? ScrollView
                            ?: return@setOnTouchListener true
                        val panelRoot = scrollView.parent as? FrameLayout
                            ?: return@setOnTouchListener true

                        scrollView.requestDisallowInterceptTouchEvent(false)
                        panelRoot.removeView(ghost)
                        ghostRef[0] = null

                        val dIdx = draggingIdx[0]
                        val tIdx = targetIdx[0]
                        draggingIdx[0] = -1
                        targetIdx[0]   = -1

                        if (dIdx >= 0 && dIdx != tIdx && event.actionMasked == MotionEvent.ACTION_UP) {
                            // Reset translations instantly — onReorder will rebuild the list.
                            rowViews.forEach { it.translationY = 0f; it.alpha = 1f }
                            val newOrder = currentOrder.toMutableList()
                            val moved = newOrder.removeAt(dIdx)
                            newOrder.add(tIdx, moved)
                            currentOrder.clear()
                            currentOrder.addAll(newOrder)
                            onReorder(newOrder)
                        } else {
                            // No move or cancelled — animate everything back.
                            rowViews.forEach { row ->
                                row.animate().translationY(0f).alpha(1f).setDuration(120).start()
                            }
                        }
                        true
                    }

                    else -> false
                }
            }
        }
    }

    for (actionRow in actionRows) {
        (actionRow.parent as? android.view.ViewGroup)?.removeView(actionRow)
        container.addView(actionRow, LinearLayout.LayoutParams(panelW, itemH))
    }
}

/**
 * Slides rows aside to visualise where the dragged item will land.
 *
 * Items between [dragIdx] and [targetIdx] shift by ±[itemH] to open a gap
 * at the target slot. The dragged row itself stays in place (it is invisible).
 */
private fun animateRowDisplacement(
    rows: List<View>,
    dragIdx: Int,
    targetIdx: Int,
    itemH: Float,
) {
    rows.forEachIndexed { i, row ->
        val ty = when {
            i == dragIdx -> 0f
            dragIdx < targetIdx && i in (dragIdx + 1)..targetIdx -> -itemH
            dragIdx > targetIdx && i in targetIdx until dragIdx  ->  itemH
            else -> 0f
        }
        row.animate().translationY(ty).setDuration(120).start()
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
    addedPackages: Collection<String>,
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
 * The selected app's icon and name are shown in the panel's ghost header (set by the caller
 * before starting the submenu-enter animation). The scroll container holds any launcher
 * shortcuts (static + dynamic) followed by management actions: Hide, Rename, App Info, Uninstall.
 *
 * @param container   The LinearLayout to populate (cleared first).
 * @param appIcon     Icon of the selected app (used for the non-interactive header row).
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
    val actionH = (48 * d).toInt()
    val itemH   = (56 * d).toInt()
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
