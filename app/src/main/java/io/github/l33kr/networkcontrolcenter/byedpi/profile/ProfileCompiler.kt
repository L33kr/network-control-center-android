package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import io.github.l33kr.networkcontrolcenter.byedpi.shellSplit

data class CompiledProfileSet(
    val command: String,
    val enabledProfiles: Int,
    val bypassProfiles: Int,
    val passProfiles: Int,
)

object ProfileCompiler {
    fun compile(context: Context, set: ProfileSetModel, sni: String): CompiledProfileSet {
        val enabled = set.profiles.filter { it.enabled }
        val output = mutableListOf<String>()

        enabled.forEach { profile ->
            val domains = ProfileStore.resolveDomains(context, profile.domainListIds)
            val restrictions = restrictionTokens(profile.protocol, domains)

            when (profile.action) {
                ProfileAction.PASS -> output += restrictions
                ProfileAction.BYPASS -> {
                    val rawCommand = profile.strategyCommand
                        ?.trim()
                        .orEmpty()
                        .ifBlank { ByeDpiStrategies.BALANCED.command }
                        .replace("{sni}", sni)
                    output += injectRestrictions(
                        args = shellSplit(rawCommand),
                        restrictions = restrictions,
                        forceProtocol = profile.protocol != ProfileProtocol.ANY,
                    )
                }
            }

            // Continue to the next profile only when the current profile was skipped
            // by host/protocol restrictions. A matched PASS profile therefore stops
            // desync processing, while a matched BYPASS profile runs its strategy.
            output += "-An"
        }

        // Terminal no-op group. This guarantees that traffic unmatched by every
        // enabled profile still has a valid PASS destination for the last -An.
        output += listOf("-K", "t,h,u,i")

        return CompiledProfileSet(
            command = output.joinToString(" ") { quoteIfNeeded(it) },
            enabledProfiles = enabled.size,
            bypassProfiles = enabled.count { it.action == ProfileAction.BYPASS },
            passProfiles = enabled.count { it.action == ProfileAction.PASS },
        )
    }

    private fun restrictionTokens(
        protocol: ProfileProtocol,
        domains: List<String>,
    ): List<String> = buildList {
        if (domains.isNotEmpty()) {
            add("-H")
            add(":${domains.joinToString(" ")}")
        }
        when (protocol) {
            ProfileProtocol.ANY -> Unit
            ProfileProtocol.TCP_TLS -> {
                add("-K")
                add("t,h")
            }
            ProfileProtocol.UDP_QUIC -> {
                add("-K")
                add("u")
            }
        }
    }

    private fun injectRestrictions(
        args: List<String>,
        restrictions: List<String>,
        forceProtocol: Boolean,
    ): List<String> {
        if (restrictions.isEmpty()) return args

        val cleaned = if (forceProtocol) stripProtocolFilters(args) else args
        return buildList {
            addAll(restrictions)
            var index = 0
            while (index < cleaned.size) {
                val token = cleaned[index]
                add(token)

                when {
                    token == "-A" || token == "--auto" -> {
                        if (index + 1 < cleaned.size) {
                            index++
                            add(cleaned[index])
                        }
                        addAll(restrictions)
                    }
                    token.startsWith("-A") && token.length > 2 -> addAll(restrictions)
                    token.startsWith("--auto=") -> addAll(restrictions)
                }
                index++
            }
        }
    }

    private fun stripProtocolFilters(args: List<String>): List<String> = buildList {
        var index = 0
        while (index < args.size) {
            val token = args[index]
            when {
                token == "-K" || token == "--proto" -> index += 2
                token.startsWith("-K") && token.length > 2 -> index++
                token.startsWith("--proto=") -> index++
                else -> {
                    add(token)
                    index++
                }
            }
        }
    }

    private fun quoteIfNeeded(value: String): String {
        if (value.none { it.isWhitespace() || it == '"' }) return value
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
