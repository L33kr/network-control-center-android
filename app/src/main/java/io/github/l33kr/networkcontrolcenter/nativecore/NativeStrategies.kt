package io.github.l33kr.networkcontrolcenter.nativecore

data class NativeStrategyPreset(
    val id: String,
    val title: String,
    val description: String,
    val technique: NativeTechnique,
    val recommended: Boolean = false,
) {
    val command: String get() = "native://$id"
}

object NativeStrategies {
    val AUTO = NativeStrategyPreset(
        id = "auto",
        title = "Adaptive",
        description = "Собственный автоматический режим Native Engine. Для TLS использует протокольно-корректный record split с коротким разнесением отправки; для HTTP — semantic multi-split.",
        technique = NativeTechnique.HYBRID,
        recommended = true,
    )

    val TLS_RECORD = NativeStrategyPreset(
        id = "tls-record",
        title = "TLS Record Split",
        description = "Разбивает ClientHello на два корректных TLS record около середины SNI, не повреждая handshake.",
        technique = NativeTechnique.TLS_RECORD_SPLIT,
        recommended = true,
    )

    val MULTI_SPLIT = NativeStrategyPreset(
        id = "multi-split",
        title = "Semantic Multi Split",
        description = "Отправляет ClientHello несколькими TCP write вокруг начала, середины и конца SNI/HTTP Host.",
        technique = NativeTechnique.MULTI_SPLIT,
        recommended = true,
    )

    val HYBRID = NativeStrategyPreset(
        id = "hybrid",
        title = "TLS Hybrid",
        description = "Сочетает корректный TLS record split с короткой задержкой между record. Это полностью наша реализация без ByeDPI.",
        technique = NativeTechnique.HYBRID,
        recommended = true,
    )

    val PASS = NativeStrategyPreset(
        id = "pass",
        title = "Без преобразований",
        description = "Прозрачная передача трафика. Полезно для исключений и диагностики.",
        technique = NativeTechnique.PASS,
    )

    val all: List<NativeStrategyPreset> = listOf(AUTO, HYBRID, TLS_RECORD, MULTI_SPLIT, PASS)

    fun byId(id: String?): NativeStrategyPreset? = all.firstOrNull { it.id == id }

    fun fromCommand(command: String?): NativeStrategyPreset? {
        val raw = command?.trim() ?: return null
        if (!raw.startsWith("native://")) return null
        return byId(raw.removePrefix("native://"))
    }
}
