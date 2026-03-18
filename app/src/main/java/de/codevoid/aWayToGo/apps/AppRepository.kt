package de.codevoid.aWayToGo.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings

data class AppInfo(
    /** Display name — custom if the user renamed the app, otherwise the system label. */
    val label: String,
    /** Always the system-assigned label, regardless of any custom name. Used for Reset. */
    val originalLabel: String,
    val packageName: String,
    val icon: Drawable,
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
        }.sortedBy { it.label.lowercase() }
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

    fun uninstallApp(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
