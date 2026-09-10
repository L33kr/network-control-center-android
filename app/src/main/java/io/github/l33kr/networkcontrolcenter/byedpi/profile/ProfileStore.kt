package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ProfileStore {
    private const val PREFS = "profile_engine_v2"
    private const val KEY_LISTS = "domain_lists"
    private const val KEY_SETS = "profile_sets"
    private const val KEY_ACTIVE_SET = "active_set"

    const val LIST_YOUTUBE = "youtube"
    const val LIST_GOOGLE_VIDEO = "google_video"
    const val LIST_DISCORD = "discord"
    const val LIST_USER = "user"
    const val LIST_IGNORE = "ignore"

    fun ensureInitialized(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LISTS)) {
            prefs.edit().putString(KEY_LISTS, encodeLists(defaultLists())).apply()
        }
        if (!prefs.contains(KEY_SETS)) {
            val sets = defaultSets()
            prefs.edit()
                .putString(KEY_SETS, encodeSets(sets))
                .putString(KEY_ACTIVE_SET, sets.first().id)
                .apply()
        }
    }

    fun loadLists(context: Context): List<DomainListModel> {
        ensureInitialized(context)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LISTS, null)
        return runCatching { decodeLists(raw.orEmpty()) }
            .getOrElse { defaultLists() }
            .ifEmpty { defaultLists() }
    }

    fun loadSets(context: Context): List<ProfileSetModel> {
        ensureInitialized(context)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SETS, null)
        return runCatching { decodeSets(raw.orEmpty()) }
            .getOrElse { defaultSets() }
            .ifEmpty { defaultSets() }
    }

    fun activeSet(context: Context): ProfileSetModel {
        val sets = loadSets(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_SET, sets.first().id)
        return sets.firstOrNull { it.id == activeId } ?: sets.first()
    }

    fun setActiveSet(context: Context, setId: String) {
        val sets = loadSets(context)
        if (sets.none { it.id == setId }) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_SET, setId)
            .apply()
    }

    fun saveSet(context: Context, set: ProfileSetModel) {
        val sets = loadSets(context).toMutableList()
        val index = sets.indexOfFirst { it.id == set.id }
        if (index >= 0) sets[index] = set else sets += set
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SETS, encodeSets(sets))
            .apply()
    }

    fun duplicateActiveSet(context: Context, name: String): ProfileSetModel {
        val active = activeSet(context)
        val copy = active.copy(
            id = "custom-${UUID.randomUUID()}",
            name = name.trim().ifBlank { "Мой набор" },
            description = "Пользовательская копия ${active.name}",
            profiles = active.profiles.map { profile ->
                profile.copy(id = "profile-${UUID.randomUUID()}")
            },
        )
        saveSet(context, copy)
        setActiveSet(context, copy.id)
        return copy
    }

    fun saveDomainList(context: Context, list: DomainListModel) {
        val lists = loadLists(context).toMutableList()
        val normalized = list.copy(domains = normalizeDomains(list.domains))
        val index = lists.indexOfFirst { it.id == normalized.id }
        if (index >= 0) lists[index] = normalized else lists += normalized
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LISTS, encodeLists(lists))
            .apply()
    }

    fun createDomainList(context: Context, name: String): DomainListModel {
        val list = DomainListModel(
            id = "list-${UUID.randomUUID()}",
            name = name.trim().ifBlank { "Новый список" },
            domains = emptyList(),
        )
        saveDomainList(context, list)
        return list
    }

    fun resetDefaults(context: Context) {
        val sets = defaultSets()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LISTS, encodeLists(defaultLists()))
            .putString(KEY_SETS, encodeSets(sets))
            .putString(KEY_ACTIVE_SET, sets.first().id)
            .apply()
    }

    fun resolveDomains(context: Context, listIds: List<String>): List<String> {
        if (listIds.isEmpty()) return emptyList()
        val byId = loadLists(context).associateBy { it.id }
        return listIds.flatMap { byId[it]?.domains.orEmpty() }.distinct()
    }

    private fun defaultLists(): List<DomainListModel> = listOf(
        DomainListModel(
            id = LIST_YOUTUBE,
            name = "YouTube",
            domains = listOf(
                "youtube.com",
                "youtu.be",
                "youtube-nocookie.com",
                "youtubei.googleapis.com",
                "youtube.googleapis.com",
                "ytimg.com",
            ),
        ),
        DomainListModel(
            id = LIST_GOOGLE_VIDEO,
            name = "GoogleVideo / CDN",
            domains = listOf(
                "googlevideo.com",
                "redirector.googlevideo.com",
                "gvt1.com",
                "ggpht.com",
                "googleusercontent.com",
            ),
        ),
        DomainListModel(
            id = LIST_DISCORD,
            name = "Discord",
            domains = listOf(
                "discord.com",
                "discord.gg",
                "discordapp.com",
                "discordapp.net",
                "discordcdn.com",
                "discord.media",
                "gateway.discord.gg",
                "media.discordapp.net",
            ),
        ),
        DomainListModel(
            id = LIST_USER,
            name = "Пользовательские домены",
            domains = emptyList(),
        ),
        DomainListModel(
            id = LIST_IGNORE,
            name = "Не обрабатывать (PASS)",
            domains = emptyList(),
        ),
    )

    private fun defaultSets(): List<ProfileSetModel> {
        val balanced = ByeDpiStrategies.BALANCED.command
        val strong = ByeDpiStrategies.STRONG_FAKE.command
        val mobile = ByeDpiStrategies.MOBILE_RU.command
        val udp = "-Ku -a1"

        return listOf(
            ProfileSetModel(
                id = "youtube-discord",
                name = "YouTube + Discord",
                description = "Раздельные TLS и QUIC профили, Discord и пользовательские домены",
                profiles = listOf(
                    TrafficProfile("pass-ignore", "Исключения", action = ProfileAction.PASS, domainListIds = listOf(LIST_IGNORE)),
                    TrafficProfile("yt-tls", "YouTube · TLS", protocol = ProfileProtocol.TCP_TLS, domainListIds = listOf(LIST_YOUTUBE), strategyName = "ByeByeDPI Default", strategyCommand = balanced),
                    TrafficProfile("gv-tls", "GoogleVideo · TLS", protocol = ProfileProtocol.TCP_TLS, domainListIds = listOf(LIST_GOOGLE_VIDEO), strategyName = "Strong / Fake", strategyCommand = strong),
                    TrafficProfile("yt-quic", "YouTube · QUIC", protocol = ProfileProtocol.UDP_QUIC, domainListIds = listOf(LIST_YOUTUBE, LIST_GOOGLE_VIDEO), strategyName = "UDP fake", strategyCommand = udp),
                    TrafficProfile("discord", "Discord", protocol = ProfileProtocol.ANY, domainListIds = listOf(LIST_DISCORD), strategyName = "ByeByeDPI Default", strategyCommand = balanced),
                    TrafficProfile("user", "Пользовательские", protocol = ProfileProtocol.ANY, domainListIds = listOf(LIST_USER), strategyName = "ByeByeDPI Default", strategyCommand = balanced),
                    TrafficProfile("default-pass", "Остальной трафик", action = ProfileAction.PASS),
                ),
            ),
            ProfileSetModel(
                id = "youtube-only",
                name = "Только YouTube",
                description = "Обход только YouTube/GoogleVideo, остальной трафик без desync",
                profiles = listOf(
                    TrafficProfile("yt-only-ignore", "Исключения", action = ProfileAction.PASS, domainListIds = listOf(LIST_IGNORE)),
                    TrafficProfile("yt-only-tls", "YouTube · TLS", protocol = ProfileProtocol.TCP_TLS, domainListIds = listOf(LIST_YOUTUBE, LIST_GOOGLE_VIDEO), strategyName = "Strong / Fake", strategyCommand = strong),
                    TrafficProfile("yt-only-quic", "YouTube · QUIC", protocol = ProfileProtocol.UDP_QUIC, domainListIds = listOf(LIST_YOUTUBE, LIST_GOOGLE_VIDEO), strategyName = "UDP fake", strategyCommand = udp),
                    TrafficProfile("yt-only-pass", "Остальной трафик", action = ProfileAction.PASS),
                ),
            ),
            ProfileSetModel(
                id = "mobile-aggressive",
                name = "Mobile · Aggressive",
                description = "Более агрессивный профиль для мобильных сетей с отдельным QUIC",
                profiles = listOf(
                    TrafficProfile("mobile-ignore", "Исключения", action = ProfileAction.PASS, domainListIds = listOf(LIST_IGNORE)),
                    TrafficProfile("mobile-yt", "YouTube + CDN · TLS", protocol = ProfileProtocol.TCP_TLS, domainListIds = listOf(LIST_YOUTUBE, LIST_GOOGLE_VIDEO), strategyName = "Mobile RU", strategyCommand = mobile),
                    TrafficProfile("mobile-quic", "YouTube · QUIC", protocol = ProfileProtocol.UDP_QUIC, domainListIds = listOf(LIST_YOUTUBE, LIST_GOOGLE_VIDEO), strategyName = "UDP fake", strategyCommand = udp),
                    TrafficProfile("mobile-discord", "Discord", protocol = ProfileProtocol.ANY, domainListIds = listOf(LIST_DISCORD), strategyName = "Strong / Fake", strategyCommand = strong),
                    TrafficProfile("mobile-user", "Пользовательские", protocol = ProfileProtocol.ANY, domainListIds = listOf(LIST_USER), strategyName = "Mobile RU", strategyCommand = mobile),
                    TrafficProfile("mobile-pass", "Остальной трафик", action = ProfileAction.PASS),
                ),
            ),
        )
    }

    private fun normalizeDomains(domains: List<String>): List<String> = domains
        .flatMap { it.split(' ', ',', ';', '\n', '\r', '\t') }
        .map { it.trim().lowercase() }
        .map { it.removePrefix("https://").removePrefix("http://").substringBefore('/') }
        .map { it.removePrefix("*.").trim('.') }
        .filter { it.isNotBlank() && it.length <= 253 }
        .distinct()

    private fun encodeLists(lists: List<DomainListModel>): String = JSONArray().apply {
        lists.forEach { list ->
            put(JSONObject().apply {
                put("id", list.id)
                put("name", list.name)
                put("domains", JSONArray(list.domains))
            })
        }
    }.toString()

    private fun decodeLists(raw: String): List<DomainListModel> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val domainsArray = obj.optJSONArray("domains") ?: JSONArray()
                val domains = buildList {
                    for (j in 0 until domainsArray.length()) add(domainsArray.optString(j))
                }
                add(
                    DomainListModel(
                        id = obj.getString("id"),
                        name = obj.optString("name", "Список"),
                        domains = normalizeDomains(domains),
                    ),
                )
            }
        }
    }

    private fun encodeSets(sets: List<ProfileSetModel>): String = JSONArray().apply {
        sets.forEach { set ->
            put(JSONObject().apply {
                put("id", set.id)
                put("name", set.name)
                put("description", set.description)
                put("profiles", JSONArray().apply {
                    set.profiles.forEach { profile ->
                        put(JSONObject().apply {
                            put("id", profile.id)
                            put("name", profile.name)
                            put("enabled", profile.enabled)
                            put("action", profile.action.name)
                            put("protocol", profile.protocol.name)
                            put("domainListIds", JSONArray(profile.domainListIds))
                            put("strategyName", profile.strategyName ?: JSONObject.NULL)
                            put("strategyCommand", profile.strategyCommand ?: JSONObject.NULL)
                        })
                    }
                })
            })
        }
    }.toString()

    private fun decodeSets(raw: String): List<ProfileSetModel> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val profilesArray = obj.optJSONArray("profiles") ?: JSONArray()
                val profiles = buildList {
                    for (j in 0 until profilesArray.length()) {
                        val p = profilesArray.getJSONObject(j)
                        val listIdsArray = p.optJSONArray("domainListIds") ?: JSONArray()
                        val listIds = buildList {
                            for (k in 0 until listIdsArray.length()) add(listIdsArray.optString(k))
                        }
                        add(
                            TrafficProfile(
                                id = p.getString("id"),
                                name = p.optString("name", "Профиль"),
                                enabled = p.optBoolean("enabled", true),
                                action = runCatching { ProfileAction.valueOf(p.optString("action")) }.getOrDefault(ProfileAction.BYPASS),
                                protocol = runCatching { ProfileProtocol.valueOf(p.optString("protocol")) }.getOrDefault(ProfileProtocol.ANY),
                                domainListIds = listIds,
                                strategyName = p.optString("strategyName").takeIf { it.isNotBlank() && it != "null" },
                                strategyCommand = p.optString("strategyCommand").takeIf { it.isNotBlank() && it != "null" },
                            ),
                        )
                    }
                }
                add(
                    ProfileSetModel(
                        id = obj.getString("id"),
                        name = obj.optString("name", "Набор"),
                        description = obj.optString("description", ""),
                        profiles = profiles,
                    ),
                )
            }
        }
    }
}
