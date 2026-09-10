package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

object StrategyPreferences {
    private const val PREFS = "strategy_preferences"
    private const val FAVORITES = "favorites"

    fun favorites(context: Context): Set<String> = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(FAVORITES, emptySet())
        ?.toSet()
        .orEmpty()

    fun toggleFavorite(context: Context, strategyId: String): Set<String> {
        val next = favorites(context).toMutableSet().apply {
            if (!add(strategyId)) remove(strategyId)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FAVORITES, next)
            .apply()
        return next
    }
}
