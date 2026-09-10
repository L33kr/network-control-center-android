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
        title = "Auto",
        description = "Автоматический режим. Пока использует безопасный Hybrid и сохраняет формат для дальнейшего обучения по сети.",
        technique = NativeTechnique.HYBRID,
        recommended = true,
    )

    val TLS_RECORD = NativeStrategyPreset(
        id = "tls-record",
        title = "TLS Record Split",
        description = "Разбивает ClientHello на два корректных TLS record в середине имени хоста.",
        technique = NativeTechnique.TLS_RECORD_SPLIT,
    )

    val MULTI_SPLIT = NativeStrategyPreset(
        id = "multi-split",
        title = "SNI Multi Split",
        description = "Передаёт ClientHello несколькими TCP-записями вокруг начала, середины и конца SNI.",
        technique = NativeTechnique.MULTI_SPLIT,
    )

    val HYBRID = NativeStrategyPreset(
        id = "hybrid",
        title = "Hybrid",
        description = "TLS Record Split плюс раздельная отправка частей с короткой задержкой.",
        technique = NativeTechnique.HYBRID,
        recommended = true,
    )

    val all: List<NativeStrategyPreset> = listOf(AUTO, HYBRID, TLS_RECORD, MULTI_SPLIT)

    fun fromCommand(command: String?): NativeStrategyPreset? {
        val id = command?.trim()?.removePrefix("native://") ?: return null
        return all.firstOrNull { it.id == id }
    }
}
