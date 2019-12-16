package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

@RestController
class V2StateController(
    val localServiceChanges: LocalServiceChanges,
    val restTemplate: RestTemplate,
    val protobufCache: StatesCachedSerializer
) {

    val logger: Logger = LoggerFactory.getLogger(V2StateController::class.java)

    @GetMapping(value = ["/v2/state"], produces = ["application/x-protobuf"])
    fun getState(): ServicesStateProto.ServicesState {
        val cachedResponse = protobufCache.get()
        if (cachedResponse != null) {
            return cachedResponse
        }
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
        val protoResult = ServicesStateProto.ServicesState.newBuilder()
            .putAllServiceNameToInstances(serviceNameToInstances)
            .build()
        val responseCache = protobufCache.serialize(protoResult)
        logger.info("Cache response = $responseCache")
        return protoResult
    }

    @GetMapping(value = ["/test"], produces = ["application/json"])
    fun getTest(): ServicesState {
        val response = restTemplate.getForEntity("http://localhost:8080/v2/state",
            ServicesStateProto.ServicesState::class.java)
        return deserializeProto(response.body)
    }

    private fun deserializeProto(body: ServicesStateProto.ServicesState?): ServicesState {
        val serviceNameToInstances = body?.serviceNameToInstances?.map { entry ->
            entry.key to ServiceInstances(
                entry.value.serviceName, entry.value.instancesList.map {
                ServiceInstance(
                    it.id,
                    it.address,
                    it.port,
                    it.tagsList.toHashSet(),
                    it.regular,
                    it.canary,
                    it.weight
                )
            }.toHashSet()
            )
        }!!.toMap()
        return ServicesState(serviceNameToInstances)
    }
}
