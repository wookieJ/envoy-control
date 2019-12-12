package pl.allegro.tech.servicemesh.envoycontrol.services

import kotlinx.serialization.Optional
import kotlinx.serialization.Serializable

@Serializable
data class ServiceInstances(
    val serviceName: String,

    @Optional
    val instances: Set<ServiceInstance> = emptySet()
) {
    fun withoutEmptyAddressInstances(): ServiceInstances =
        if (instances.any { it.address.isBlank() }) {
            copy(instances = instances.asSequence()
                .filter { it.address.isNotBlank() }
                .toSet())
        } else this
}
