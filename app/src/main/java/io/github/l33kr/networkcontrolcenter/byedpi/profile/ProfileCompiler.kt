package io.github.l33kr.networkcontrolcenter.byedpi.profile

import android.content.Context
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategies
import io.github.l33kr.networkcontrolcenter.byedpi.ByeDpiStrategyPlan
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
                    val args = shellSplit(rawCommand)

                    output += if (ByeDpiStrategyPlan.isMultiStage(rawCommand)) {
                        scopeMultiStageStrategy(
                            args = args,
                            protocol = profile.protocol,
                            domains = domains,
                        )
                    } else {
                        injectRestrictions(
                            args = args,
                            restrictions = restrictions,
                            forceProtocol = profile.protocol != ProfileProtocol.ANY,
                        )
                    }
                }
            }

            // Move to the next profile only when the current one did not match.
            // Internal -A chains inside a BYPASS strategy remain intact and run first.
            output += "-An"
        }

        // Terminal no-op/pass group for traffic unmatched by every profile.
        output += listOf("-K", "t,h,u,i")

        return CompiledProfileSet(
            command = output.joinToString(" ") { quoteIfNeeded(it) },
            enabledProfiles = enabled.size,
            bypassProfiles = enabled.count { it.action == ProfileAction.BYPASS },
            passProfiles = enabled.count { it.action == ProfileAction.PASS },
        )
    }

    /**
     * A real ByeDPI strategy may contain several fallback stages delimited by -A.
     * For such commands we must not strip or replace their own -K filters because
     * those filters can be part of the stage logic. We only repeat the external
     * profile scope at every stage boundary.
     */
    private fun scopeMultiStageStrategy(
        args: List<String>,
        protocol: ProfileProtocol,
        domains: List<String>,
    ): List<String> {
        val containsProtocolFilter = args.any(::isProtocolToken)
        val scope = buildList {
            if (domains.isNotEmpty()) {
                add("-H")
                add(":${domains.joinToString(" ")}")
            }
            if (!containsProtocolFilter) {
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
        }

        if (scope.isEmpty()) return args

        return buildList {
            addAll(scope)
            var index = 0
            while (index < args.size) {
                val token = args[index]
                add(token)

                when {
                    token == "-A" || token == "--auto" -> {
                        if (index + 1 < args.size) {
                            index++
                            add(args[index])
                        }
                        addAll(scope)
                    }
                    token.startsWith("-A") && token.length > 2 -> addAll(scope)
                    token.startsWith("--auto=") -> addAll(scope)
                }
                index++
            }
        }
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

    private fun isProtocolToken(token: String): Boolean =
        token == "-K" || token == "--proto" ||
            (token.startsWith("-K") && token.length > 2) ||
            token.startsWith("--proto=")

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
