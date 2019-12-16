package pl.allegro.tech.servicemesh.envoycontrol.services

data class ServiceInstance(
    val id: String,
    val address: String,
    val port: Int,
    val tags: Set<String> = emptySet(),
    val regular: Boolean = true,
    val canary: Boolean = false,
    val weight: Int = 1
)
