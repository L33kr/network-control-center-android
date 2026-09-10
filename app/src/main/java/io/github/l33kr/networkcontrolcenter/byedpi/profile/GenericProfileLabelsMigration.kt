package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import org.json.JSONArray

/**
 * Keeps the v2 UI service-neutral without deleting the built-in domain coverage.
 *
 * Existing 0.5-dev installs may already have the original service-specific names
 * stored in SharedPreferences, so changing only the defaults would not update
 * them. This migration renames only untouched built-in labels/sets by their
 * stable ids and preserves domains, strategies and user-created configuration.
 */
object GenericProfileLabelsMigration {
    private const val PREFS = "profile_engine_v2"
    private const val KEY_LISTS = "domain_lists"
    private const val KEY_SETS = "profile_sets"

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false

        prefs.getString(KEY_LISTS, null)?.let { raw ->
            runCatching {
                val lists = JSONArray(raw)
                for (index in 0 until lists.length()) {
                    val item = lists.getJSONObject(index)
                    val next = genericListName(item.optString("id"), item.optString("name"))
                    if (next != null && next != item.optString("name")) {
                        item.put("name", next)
                        changed = true
                    }
                }
                if (changed) editor.putString(KEY_LISTS, lists.toString())
            }
        }

        var setsChanged = false
        prefs.getString(KEY_SETS, null)?.let { raw ->
            runCatching {
                val sets = JSONArray(raw)
                for (index in 0 until sets.length()) {
                    val set = sets.getJSONObject(index)
                    val setId = set.optString("id")
                    val oldName = set.optString("name")
                    val oldDescription = set.optString("description")
                    val nextName = genericSetName(setId, oldName)
                    val nextDescription = genericSetDescription(setId, oldDescription)
                    if (nextName != null && nextName != oldName) {
                        set.put("name", nextName)
                        setsChanged = true
                    }
                    if (nextDescription != null && nextDescription != oldDescription) {
                        set.put("description", nextDescription)
                        setsChanged = true
                    }

                    val profiles = set.optJSONArray("profiles") ?: continue
                    for (profileIndex in 0 until profiles.length()) {
                        val profile = profiles.getJSONObject(profileIndex)
                        val oldProfileName = profile.optString("name")
                        val nextProfileName = genericProfileName(profile.optString("id"), oldProfileName)
                        if (nextProfileName != null && nextProfileName != oldProfileName) {
                            profile.put("name", nextProfileName)
                            setsChanged = true
                        }
                    }
                }
                if (setsChanged) editor.putString(KEY_SETS, sets.toString())
            }
        }

        if (changed || setsChanged) editor.apply()
    }

    private fun genericListName(id: String, current: String): String? = when (id) {
        "youtube" -> if (current == "YouTube") "Веб" else null
        "google_video" -> if (current == "GoogleVideo / CDN") "Медиа / CDN" else null
        "discord" -> if (current == "Discord") "Realtime" else null
        "user" -> if (current == "Пользовательские домены") "Мои домены" else null
        "ignore" -> if (current == "Не обрабатывать (PASS)") "Исключения (PASS)" else null
        else -> null
    }

    private fun genericSetName(id: String, current: String): String? = when (id) {
        "youtube-discord" -> if (current == "YouTube + Discord") "Универсальный" else null
        "youtube-only" -> if (current == "Только YouTube") "Основные домены" else null
        "mobile-aggressive" -> if (current == "Mobile · Aggressive") "Мобильная сеть · усиленный" else null
        else -> null
    }

    private fun genericSetDescription(id: String, current: String): String? = when (id) {
        "youtube-discord" -> if (current.contains("Discord") || current.contains("YouTube")) {
            "Готовая конфигурация для веб, медиа/CDN, realtime и ваших доменов"
        } else null
        "youtube-only" -> if (current.contains("YouTube") || current.contains("GoogleVideo")) {
            "Обработка основного веб- и медиатрафика; остальной трафик без изменений"
        } else null
        "mobile-aggressive" -> if (current.contains("мобиль", ignoreCase = true) || current.contains("QUIC")) {
            "Усиленная конфигурация для мобильной сети с отдельной обработкой QUIC"
        } else null
        else -> null
    }

    private fun genericProfileName(id: String, current: String): String? = when (id) {
        "yt-tls" -> if (current.contains("YouTube")) "Веб · TLS" else null
        "gv-tls" -> if (current.contains("GoogleVideo")) "Медиа · TLS" else null
        "yt-quic" -> if (current.contains("YouTube")) "Веб / медиа · QUIC" else null
        "discord" -> if (current == "Discord") "Realtime" else null
        "user" -> if (current == "Пользовательские") "Мои домены" else null

        "yt-only-tls" -> if (current.contains("YouTube")) "Веб / медиа · TLS" else null
        "yt-only-quic" -> if (current.contains("YouTube")) "Веб / медиа · QUIC" else null

        "mobile-yt" -> if (current.contains("YouTube")) "Веб / медиа · TLS" else null
        "mobile-quic" -> if (current.contains("YouTube")) "Веб / медиа · QUIC" else null
        "mobile-discord" -> if (current == "Discord") "Realtime" else null
        "mobile-user" -> if (current == "Пользовательские") "Мои домены" else null

        else -> null
    }
}
