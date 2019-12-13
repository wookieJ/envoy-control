package pl.allegro.tech.servicemesh.envoycontrol.services

import kotlinx.serialization.SerialId
import kotlinx.serialization.Serializable

@Serializable
data class ServiceInstances(
    @SerialId(1) val serviceName: String,
    @SerialId(2) val instances: Set<ServiceInstance> = emptySet()
) {
    fun withoutEmptyAddressInstances(): ServiceInstances =
        if (instances.any { it.address.isBlank() }) {
            copy(instances = instances.asSequence()
                .filter { it.address.isNotBlank() }
                .toSet())
        } else this
}
