package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import kotlinx.serialization.protobuf.ProtoBuf
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import reactor.core.publisher.Mono
import java.net.URI

class AsyncRestTemplateControlPlaneClient(val asyncRestTemplate: AsyncRestTemplate) : AsyncControlPlaneClient {
    override fun getState(uri: URI): Mono<ServicesState> =
        asyncRestTemplate.getForEntity<ByteArray>("$uri/v2/state", ByteArray::class.java)
            .completable()
            .thenApply { deserializeProto(it.body) }
            .let { Mono.fromCompletionStage(it) }

    private fun deserializeProto(body: ByteArray?): ServicesState {
        if (body == null) {
            return ServicesState()
        }
        return ProtoBuf.load(ServicesState.serializer(), body)
    }
}
