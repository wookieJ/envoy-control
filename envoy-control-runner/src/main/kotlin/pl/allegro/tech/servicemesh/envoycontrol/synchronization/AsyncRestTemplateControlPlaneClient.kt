package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.logger
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

    private val logger by logger()

    override fun getState(uri: URI): Mono<ServicesState> {
        logger.debug("getState called")
        return Mono.fromCompletionStage {
            asyncRestTemplate.getForEntity("$uri/state", ServicesState::class.java)
                .completable()
        }
            .map { it.body }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getStateGzip(uri: URI): Mono<ServicesState> {
        logger.debug("getStateGzip called")
        val entity = HttpEntity(null, HttpHeaders().apply { add(HttpHeaders.ACCEPT_ENCODING, "gzip") })
        return Mono.fromCompletionStage {
            asyncRestTemplate.exchange(
                "$uri/state",
                HttpMethod.GET,
                entity,
                ServicesState::class.java
            )
                .completable()
        }
            .map { it.body }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-state-gzip.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getV2State(uri: URI): Mono<ServicesState> {
        logger.debug("getV2State called")
        return Mono.fromCompletionStage {
            asyncRestTemplateProto.getForEntity<ServicesStateProto.ServicesState>("$uri/v2/state",
                ServicesStateProto.ServicesState::class.java)
                .completable()
        }
            .map { deserializeProto(it.body) }
            .elapsed()
            .map { t ->
                meterRegistry.timer("sync-dc-get-v2-state.time").record(t.t1, TimeUnit.MILLISECONDS)
                t.t2
            }
    }

    override fun getV2StateGzip(uri: URI): Mono<ServicesState> {
        logger.debug("getV2StateGzip called")
        val entity = HttpEntity(null, HttpHeaders().apply { add(HttpHeaders.ACCEPT_ENCODING, "gzip") })

        return Mono.fromCompletionStage {
            asyncRestTemplate.exchange(
                "$uri/v2/state",
                HttpMethod.GET,
                entity,
                ServicesStateProto.ServicesState::class.java
            )
                .completable()
        }
            .map { deserializeProto(it.body) }
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
