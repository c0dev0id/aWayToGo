package de.codevoid.aWayToGo.apps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.Settings

data class AppInfo(
    /** Display name — custom if the user renamed the app, otherwise the system label. */
    val label: String,
    /** Always the system-assigned label, regardless of any custom name. Used for Reset. */
    val originalLabel: String,
    val packageName: String,
    val icon: Drawable,
)

/** A single launcher shortcut for an app (static or dynamic). */
data class AppShortcutInfo(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

class AppRepository(
    private val context: Context,
    private val addedApps: AddedApps,
) {
    private val pm = context.packageManager
    private val selfPackage = context.packageName

    /** Full query of all launchable apps.  Only call this for the "Add App" submenu. */
    fun queryAllApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != selfPackage }
            .map { ri ->
                val systemLabel = ri.loadLabel(pm).toString()
                AppInfo(
                    label         = systemLabel,
                    originalLabel = systemLabel,
                    packageName   = ri.activityInfo.packageName,
                    icon          = ri.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Returns info for the saved "added" packages.
     * Skips unresolvable ones silently.
     * Uses any custom name stored in [AddedApps] as the display label.
     */
    fun getAddedApps(): List<AppInfo> {
        return addedApps.getAdded().mapNotNull { pkg ->
            try {
                val ai          = pm.getApplicationInfo(pkg, 0)
                val systemLabel = ai.loadLabel(pm).toString()
                AppInfo(
                    label         = addedApps.getCustomName(pkg) ?: systemLabel,
                    originalLabel = systemLabel,
                    packageName   = pkg,
                    icon          = ai.loadIcon(pm),
                )
            } catch (_: PackageManager.NameNotFoundException) { null }
        }
    }

    fun launchApp(packageName: String) {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Returns the static and dynamic launcher shortcuts for [packageName].
     * Returns an empty list when the app isn't the shortcut host (not the default launcher)
     * or when the package has no shortcuts.
     */
    fun getAppShortcuts(packageName: String): List<AppShortcutInfo> {
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        if (!la.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(packageName)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        }
        return try {
            la.getShortcuts(query, Process.myUserHandle())
                ?.filter { it.isEnabled }
                ?.map { info ->
                    AppShortcutInfo(
                        id          = info.id,
                        packageName = info.getPackage(),
                        label       = (info.shortLabel ?: info.longLabel ?: info.id).toString(),
                        icon        = la.getShortcutIconDrawable(info, 0),
                    )
                }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun launchShortcut(shortcut: AppShortcutInfo) {
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        try {
            la.startShortcut(shortcut.packageName, shortcut.id, null, null, Process.myUserHandle())
        } catch (_: Exception) { }
    }

    fun uninstallApp(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
