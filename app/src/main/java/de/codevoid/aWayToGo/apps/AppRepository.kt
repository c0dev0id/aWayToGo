package de.codevoid.aWayToGo.apps

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

class AppRepository(
    private val context: Context,
    private val hiddenApps: HiddenApps,
) {
    private val pm = context.packageManager
    private val selfPackage = context.packageName

    fun getVisibleApps(): List<AppInfo> = queryApps().filter { !hiddenApps.isHidden(it.packageName) }

    fun getHiddenApps(): List<AppInfo> = queryApps().filter { hiddenApps.isHidden(it.packageName) }

    private fun queryApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != selfPackage }
            .map { ri ->
                AppInfo(
                    label = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName,
                    icon = ri.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
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
