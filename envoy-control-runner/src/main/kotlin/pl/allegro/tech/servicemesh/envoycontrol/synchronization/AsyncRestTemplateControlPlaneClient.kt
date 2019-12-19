package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI

class AsyncRestTemplateControlPlaneClient(
    val asyncRestTemplate: AsyncRestTemplate,
    val asyncRestTemplateProto: AsyncRestTemplate,
    val meterRegistry: MeterRegistry
) : AsyncControlPlaneClient {

    override fun getState(uri: URI): Mono<ServicesState> {
        val sample = Timer.start(meterRegistry)
        return asyncRestTemplate.getForEntity("$uri/state", ServicesState::class.java)
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
            .doOnNext { sample.stop(meterRegistry.timer("sync-dc-get-state.time")) }
    }

    override fun getStateGzip(uri: URI): Mono<ServicesState> {
        val entity = HttpEntity(mapOf("accept-encoding" to "gzip"))
        val sample = Timer.start(meterRegistry)
        return asyncRestTemplate.exchange(
            "$uri/state",
            HttpMethod.GET,
            entity,
            ServicesState::class.java
        )
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
            .doOnNext { sample.stop(meterRegistry.timer("sync-dc-get-state-gzip.time")) }
    }

    override fun getV2State(uri: URI): Mono<ServicesState> {
        val sample = Timer.start(meterRegistry)
        return asyncRestTemplateProto.getForEntity<ServicesStateProto.ServicesState>("$uri/v2/state",
            ServicesStateProto.ServicesState::class.java)
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let { Mono.fromCompletionStage(it) }
            .doOnNext { sample.stop(meterRegistry.timer("sync-dc-get-v2-state.time")) }
    }

    override fun getV2StateGzip(uri: URI): Mono<ServicesState> {
        val entity = HttpEntity(mapOf("accept-encoding" to "gzip"))
        val sample = Timer.start(meterRegistry)
        return asyncRestTemplateProto.exchange(
            "$uri/v2/state",
            HttpMethod.GET,
            entity,
            ServicesStateProto.ServicesState::class.java
        )
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let { Mono.fromCompletionStage(it) }
            .doOnNext { sample.stop(meterRegistry.timer("sync-dc-get-v2-state-gzip.time")) }
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
