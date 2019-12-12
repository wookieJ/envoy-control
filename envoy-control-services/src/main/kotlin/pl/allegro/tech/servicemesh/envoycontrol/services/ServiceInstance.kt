package pl.allegro.tech.servicemesh.envoycontrol.services

import kotlinx.serialization.Optional
import kotlinx.serialization.SerialId
import kotlinx.serialization.Serializable

@Serializable
data class ServiceInstance(
    @SerialId(1) val id: String,
    @SerialId(2) val address: String,
    @SerialId(3) val port: Int,
    @SerialId(4) @Optional val tags: Set<String> = emptySet(),
    @SerialId(5) val regular: Boolean = true,
    @SerialId(6) val canary: Boolean = false,
    @SerialId(7) val weight: Int = 1
)
