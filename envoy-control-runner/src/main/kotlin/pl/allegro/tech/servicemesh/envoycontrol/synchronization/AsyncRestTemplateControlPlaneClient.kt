package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.CompletableFuture
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
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
        return asyncRestTemplate.getForEntity("$uri/state", ServicesState::class.java)
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getStateGzip(uri: URI): Mono<ServicesState> {
        val entity = HttpEntity(mapOf("accept-encoding" to "gzip"))
        return asyncRestTemplate.exchange(
            "$uri/state",
            HttpMethod.GET,
            entity,
            ServicesState::class.java
        )
            .completable()
            .thenApply { it.body }
            .let { Mono.fromCompletionStage<ServicesState?>(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-state-gzip.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getV2State(uri: URI): Mono<ServicesState> {
        return asyncRestTemplateProto.getForEntity<ServicesStateProto.ServicesState>("$uri/v2/state",
            ServicesStateProto.ServicesState::class.java)
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let<CompletableFuture<ServicesState>?, Mono<ServicesState>> { Mono.fromCompletionStage(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-v2-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getV2StateGzip(uri: URI): Mono<ServicesState> {
        val entity = HttpEntity(mapOf("accept-encoding" to "gzip"))
        return asyncRestTemplateProto.exchange(
            "$uri/v2/state",
            HttpMethod.GET,
            entity,
            ServicesStateProto.ServicesState::class.java
        )
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let<CompletableFuture<ServicesState>?, Mono<ServicesState>> { Mono.fromCompletionStage(it) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-v2-state-gzip.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
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
