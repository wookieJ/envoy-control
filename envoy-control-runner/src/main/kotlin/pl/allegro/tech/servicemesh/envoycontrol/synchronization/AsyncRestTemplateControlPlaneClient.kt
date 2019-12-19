package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances

class AsyncRestTemplateControlPlaneClient(
    val asyncRestTemplate: AsyncRestTemplate,
    val meterRegistry: MeterRegistry
) : AsyncControlPlaneClient {

    override fun getState(uri: URI): Mono<ServicesState> {
        val sample = Timer.start(meterRegistry)
        val response = asyncRestTemplate.getForEntity<ServicesState>("$uri/state", ServicesState::class.java)
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
        sample.stop(meterRegistry.timer("sync-dc-get-state.time"))
        return response
    }

    override fun getV2State(uri: URI): Mono<ServicesState> {
        val sample = Timer.start(meterRegistry)
        val response = asyncRestTemplate.getForEntity<ServicesStateProto.ServicesState>("$uri/v2/state",
            ServicesStateProto.ServicesState::class.java)
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let { Mono.fromCompletionStage(it) }
        sample.stop(meterRegistry.timer("sync-dc-get-v2-state.time"))
        return response
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
