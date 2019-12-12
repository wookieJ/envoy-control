package pl.allegro.tech.servicemesh.envoycontrol.services

import kotlinx.serialization.Serializable

@Serializable
data class ServiceInstance(
    val id: String,
    val tags: Set<String>,
    val address: String,
    val port: Int,
    val regular: Boolean = true,
    val canary: Boolean = false,
    val weight: Int = 1
)
