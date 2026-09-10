package io.github.l33kr.networkcontrolcenter.byedpi

data class DomainPreset(
    val title: String,
    val domains: List<String>,
)

object DomainPresets {
    val youtube = DomainPreset(
        title = "YouTube",
        domains = listOf(
            "youtube.com",
            "youtu.be",
            "googlevideo.com",
            "ytimg.com",
            "youtubei.googleapis.com",
            "youtube.googleapis.com",
        ),
    )

    val discord = DomainPreset(
        title = "Discord",
        domains = listOf(
            "discord.com",
            "discord.gg",
            "discordapp.com",
            "discordapp.net",
            "discordcdn.com",
            "discord.media",
        ),
    )

    val googleMedia = DomainPreset(
        title = "Google media",
        domains = listOf(
            "googlevideo.com",
            "gvt1.com",
            "ggpht.com",
            "googleusercontent.com",
        ),
    )

    val all = listOf(youtube, discord, googleMedia)

    fun merge(existing: String, preset: DomainPreset): String {
        val current = existing
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
        current += preset.domains
        return current.joinToString("\n")
    }
}
