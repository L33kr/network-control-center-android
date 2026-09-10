package io.github.l33kr.networkcontrolcenter.byedpi

/**
 * Parsed view of a ByeDPI command. The raw command remains the source of truth;
 * this model only exposes the fallback stages delimited by -A/--auto.
 */
data class ByeDpiStrategyStage(
    val index: Int,
    val trigger: String?,
    val arguments: List<String>,
)

object ByeDpiStrategyPlan {
    fun parse(command: String): List<ByeDpiStrategyStage> {
        val tokens = shellSplit(command)
        if (tokens.isEmpty()) return emptyList()

        val result = mutableListOf<ByeDpiStrategyStage>()
        var current = mutableListOf<String>()
        var trigger: String? = null
        var i = 0

        fun flush() {
            if (current.isEmpty() && result.isNotEmpty()) return
            result += ByeDpiStrategyStage(
                index = result.size + 1,
                trigger = trigger,
                arguments = current.toList(),
            )
            current = mutableListOf()
        }

        while (i < tokens.size) {
            val token = tokens[i]
            val auto = autoValue(token, tokens.getOrNull(i + 1))
            if (auto != null) {
                flush()
                trigger = auto.first
                i += auto.second
            } else {
                current += token
                i++
            }
        }
        flush()
        return result.filter { it.arguments.isNotEmpty() }
    }

    fun isMultiStage(command: String): Boolean = parse(command).size > 1

    fun stageCount(command: String): Int = parse(command).size.coerceAtLeast(1)

    /**
     * Adds line breaks only at existing auto-group boundaries. Whitespace is not
     * semantically significant to shellSplit, so this remains executable as-is.
     */
    fun formatForDisplay(command: String): String {
        val stages = parse(command)
        if (stages.size <= 1) return command.trim()
        return stages.joinToString("\n") { stage ->
            val prefix = stage.trigger?.let { "-A$it " }.orEmpty()
            prefix + stage.arguments.joinToString(" ")
        }
    }

    fun triggerLabel(trigger: String?): String = when {
        trigger == null -> "Старт"
        trigger == "n" -> "если предыдущая группа не подошла"
        trigger.contains('t') && trigger.contains('r') && trigger.contains('s') -> "при timeout/reset, redirect или SSL error"
        trigger.contains('t') && trigger.contains('r') -> "при timeout/reset или redirect"
        trigger.contains('t') && trigger.contains('s') -> "при timeout/reset или SSL error"
        trigger.contains('r') && trigger.contains('s') -> "при redirect или SSL error"
        trigger.contains('t') -> "при timeout/reset"
        trigger.contains('r') -> "при redirect"
        trigger.contains('s') -> "при SSL error"
        else -> "условие: $trigger"
    }

    /** Pair(value, consumed token count). */
    private fun autoValue(token: String, next: String?): Pair<String, Int>? = when {
        token == "-A" || token == "--auto" -> {
            next?.takeIf { it.isNotBlank() }?.let { it to 2 }
        }
        token.startsWith("-A") && token.length > 2 -> token.substring(2) to 1
        token.startsWith("--auto=") -> token.substringAfter('=') to 1
        else -> null
    }
}
