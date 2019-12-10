package pl.allegro.tech.servicemesh.envoycontrol.services

import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import java.util.concurrent.atomic.AtomicReference

interface LocalServiceChangesProto : ServiceChanges {
    val latestServiceState: AtomicReference<ServicesStateProto.ServicesState>
    fun isServiceStateLoaded(): Boolean
}
