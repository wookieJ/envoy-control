package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI
import java.util.concurrent.TimeUnit

class AsyncRestTemplateControlPlaneClient(
    val asyncRestTemplate: AsyncRestTemplate,
    val asyncRestTemplateProto: AsyncRestTemplate,
    val meterRegistry: MeterRegistry
) : AsyncControlPlaneClient {

    override fun getState(uri: URI): Mono<ServicesState> {
        val response = asyncRestTemplate.getForEntity<ServicesState>("$uri/state", ServicesState::class.java)
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
        return response
    }

    override fun getV2State(uri: URI): Mono<ServicesState> {
        val response = asyncRestTemplateProto.getForEntity<ServicesStateProto.ServicesState>("$uri/v2/state",
            ServicesStateProto.ServicesState::class.java)
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let { Mono.fromCompletionStage(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-v2-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
        return response
    }

    private fun deserializeProto(body: ServicesStateProto.ServicesState?): ServicesState {
        val serviceNameToInstances = body?.serviceNameToInstancesMap?.map { entry ->
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
