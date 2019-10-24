package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot


internal data class SnapshotWithMetadata(
    val snapshot: Snapshot,
    val routesByService: Map<String, List<RouteSpecification>>
)