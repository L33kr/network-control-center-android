package io.github.l33kr.networkcontrolcenter.byedpi.profile

enum class ProfileAction {
    BYPASS,
    PASS,
}

enum class ProfileProtocol {
    ANY,
    TCP_TLS,
    UDP_QUIC,
}

data class DomainListModel(
    val id: String,
    val name: String,
    val domains: List<String>,
)

data class TrafficProfile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val action: ProfileAction = ProfileAction.BYPASS,
    val protocol: ProfileProtocol = ProfileProtocol.ANY,
    val domainListIds: List<String> = emptyList(),
    val strategyName: String? = null,
    val strategyCommand: String? = null,
)

data class ProfileSetModel(
    val id: String,
    val name: String,
    val description: String,
    val profiles: List<TrafficProfile>,
)
