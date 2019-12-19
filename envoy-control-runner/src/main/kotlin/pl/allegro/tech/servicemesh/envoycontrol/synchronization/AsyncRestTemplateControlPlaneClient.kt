package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

class AsyncRestTemplateControlPlaneClient(
    private val asyncRestTemplate: AsyncRestTemplate,
    private val meterRegistry: MeterRegistry
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
}
