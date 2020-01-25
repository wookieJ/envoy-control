package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.SimpleCache
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.Test
import org.mockito.Mockito
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers


class SnapshotUpdaterBackpressureTest {


    @Test
    fun `aaa`() {

        val cacheMock = Mockito.mock(SimpleCache::class.java) as SimpleCache<Group>
        val snapshotUpdateScheduler = Schedulers.newSingle("snapshot-update")

        val onGroupAdded: Flux<List<Group>> = Flux.just(emptyList())
        val serviceChanges: Flux<List<LocalityAwareServicesState>> = Flux.just(emptyList())



        val updater = SnapshotUpdater(
            cacheMock,
            SnapshotProperties(),
            snapshotUpdateScheduler,
            onGroupAdded,
            SimpleMeterRegistry()
        )

        // when

        updater.start(serviceChanges)
            .subscribe()
    }

}
