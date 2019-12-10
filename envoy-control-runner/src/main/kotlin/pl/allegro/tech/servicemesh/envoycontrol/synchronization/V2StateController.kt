package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges

@RestController
class V2StateController(val localServiceChanges: LocalServiceChanges) {

    @GetMapping(value = ["/v2/state"], produces = ["application/x-protobuf"])
    fun getState(): ServicesStateProto.ServicesState {
        val localServiceState = localServiceChanges.latestServiceState.get()
        val serviceNameToInstances = localServiceState.serviceNameToInstances.map { entry ->
            entry.key to ServicesStateProto.ServiceInstances.newBuilder()
                .setServiceName(entry.value.serviceName)
                .addAllInstances(entry.value.instances.map {
                    ServicesStateProto.ServiceInstance.newBuilder()
                        .setId(it.id)
                        .addAllTags(it.tags.toHashSet())
                        .setAddress(it.address)
                        .setPort(it.port)
                        .setRegular(it.regular)
                        .setCanary(it.canary)
                        .setWeight(it.weight)
                        .build()
                }.toHashSet())
                .build()
        }.toMap()
        return ServicesStateProto.ServicesState.newBuilder()
            .putAllServiceNameToInstances(serviceNameToInstances)
            .build()
    }
}
