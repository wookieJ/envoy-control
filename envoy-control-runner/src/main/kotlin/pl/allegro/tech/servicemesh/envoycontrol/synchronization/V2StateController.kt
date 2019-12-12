package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import kotlinx.serialization.protobuf.ProtoBuf
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState

@RestController
class V2StateController(val localServiceChanges: LocalServiceChanges) {

    @GetMapping(value = ["/v2/state"], produces = ["application/x-protobuf"])
    fun getState(): ByteArray {
        val localServiceState = localServiceChanges.latestServiceState.get()
        return ProtoBuf.dump(ServicesState.serializer(), localServiceState)
    }
}
