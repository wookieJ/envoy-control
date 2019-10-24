package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance


internal data class SnapshotWithMetadata(
    val snapshot: Snapshot,
    val routesByService: Map<String, List<RouteSpecification>>
)

internal data class InstanceWithMetadata(
    val instance: ServiceInstance,
    val locality: Locality,
    val zone: String
)

internal fun List<InstanceWithMetadata>.filterByLocality(locality: Locality, zone: String) = filter {
    it.locality == locality && it.zone == zone
}