package io.github.l33kr.networkcontrolcenter.byedpi

import android.content.Context

data class CatalogStrategy(
    val index: Int,
    val name: String,
    val command: String,
)

object ByeDpiStrategyCatalog {
    fun load(context: Context): List<CatalogStrategy> {
        return context.assets.open("byedpi_strategies.list")
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapIndexed { index, command ->
                        CatalogStrategy(
                            index = index,
                            name = "Готовая стратегия ${index + 1}",
                            command = command,
                        )
                    }
                    .toList()
            }
    }
}
