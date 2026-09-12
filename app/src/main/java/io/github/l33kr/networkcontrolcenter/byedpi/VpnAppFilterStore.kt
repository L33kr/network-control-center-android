package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context
import android.content.Intent

data class VpnAppEntry(
    val label: String,
    val packageName: String,
)

object VpnAppFilterStore {
    private const val PREFS = "vpn_app_filter"
    private const val KEY_EXCLUDED = "excluded_packages"

    fun excludedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED, emptySet())
            ?.toSet()
            .orEmpty()

    fun setExcluded(context: Context, packageName: String, excluded: Boolean) {
        val next = excludedPackages(context).toMutableSet()
        if (excluded) next += packageName else next -= packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_EXCLUDED, next)
            .apply()
    }

    @Suppress("DEPRECATION")
    fun launchableApps(context: Context): List<VpnAppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                VpnAppEntry(
                    label = runCatching { info.loadLabel(pm).toString() }
                        .getOrDefault(packageName)
                        .ifBlank { packageName },
                    packageName = packageName,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
