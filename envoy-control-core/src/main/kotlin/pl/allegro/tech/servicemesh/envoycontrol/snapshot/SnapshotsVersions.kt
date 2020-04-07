package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.Listener
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * We leverage the fact that when Envoy connects to xDS it starts with empty version, therefore we don't have to
 * maintain consistent versioning between Envoy Control instances.
 *
 * We have to generate new version by comparing it to the previously sent data. We cannot use hashes of data because
 * we would end up in hash collisions which would result in change in discovery that is not sent to Envoys.
 *
 * Calls for the version methods are thread safe.
 * The concurrent execution of version and retainGroups methods can lead to a situation where after retainGroups
 * invocation the group is still there. This is fine, it will be removed on the next retainGroups invocation.
 * We don't need strong consistency there.
 */
internal class SnapshotsVersions {

    private val versions = ConcurrentHashMap<Group, VersionsWithData>()

    fun version(
        group: Group,
        clusters: List<Cluster>,
        endpoints: List<ClusterLoadAssignment>,
        listeners: List<Listener> = listOf()
    ): Version {
        val versionsWithData = versions.compute(group) { _, previous ->
            val version = when (previous) {
                null -> Version(
                        clusters = ClustersVersion(newVersion()),
                        endpoints = EndpointsVersion(newVersion()),
                        listeners = ListenersVersion(newVersion()),
                        routes = RoutesVersion(newVersion())
                )
                else -> {
                    val clustersChanged = previous.clusters != clusters
                    Version(
                        clusters = selectClusters(previous, clustersChanged),
                        endpoints = selectEndpoints(previous, endpoints, clustersChanged),
                        listeners = selectListeners(previous, previous.listeners != listeners),
                        routes = selectRoutes(previous, previous.listeners != listeners)
                    )
                }
            }
            VersionsWithData(version, clusters, endpoints, listeners)
        }
        return versionsWithData!!.version
    }

    private fun selectRoutes(previous: VersionsWithData, haveListenersChanged: Boolean): RoutesVersion {
        return if (haveListenersChanged) RoutesVersion(newVersion()) else previous.version.routes
    }

    private fun selectListeners(previous: VersionsWithData, hasChanged: Boolean): ListenersVersion {
        return if (hasChanged) ListenersVersion(newVersion()) else previous.version.listeners
    }

    /**
     * When cluster change we should also send EDS.
     */
    private fun selectEndpoints(
        previous: VersionsWithData,
        endpoints: List<ClusterLoadAssignment>,
        clusterChanged: Boolean
    ) = if (!clusterChanged && previous.endpoints == endpoints) {
        previous.version.endpoints
    } else {
        EndpointsVersion(newVersion())
    }

    private fun selectClusters(
        previous: VersionsWithData,
        clustersChanged: Boolean
    ) = if (!clustersChanged) previous.version.clusters else ClustersVersion(newVersion())

    /**
     * This should be called before setting new snapshot to cache. The cache cleans up not used groups by using
     * SnapshotCollectingCallback. This should be executed so we won't store versions for stale groups.
     */
    fun retainGroups(groups: Iterable<Group>) {
        val toRemove = versions.keys - groups
        toRemove.forEach { group -> versions.remove(group) }
    }

    private fun newVersion(): String = UUID.randomUUID().toString().replace("-", "")

    private data class VersionsWithData(
        val version: Version,
        val clusters: List<Cluster>,
        val endpoints: List<ClusterLoadAssignment>,
        val listeners: List<Listener>
    )

    internal data class Version(
        val clusters: ClustersVersion,
        val endpoints: EndpointsVersion,
        val listeners: ListenersVersion,
        val routes: RoutesVersion
    )
}

data class ClustersVersion(val value: String) {
    companion object {
        val EMPTY_VERSION = ClustersVersion("empty")
    }
}

data class EndpointsVersion(val value: String) {
    companion object {
        val EMPTY_VERSION = EndpointsVersion("empty")
    }
}

data class RoutesVersion(val value: String) {
    companion object {
        val EMPTY_VERSION = RoutesVersion("empty")
    }
}

data class ListenersVersion(val value: String) {
    companion object {
        val EMPTY_VERSION = ListenersVersion("empty")
    }
}

data class SecretsVersion(val value: String) {
    companion object {
        val EMPTY_VERSION = SecretsVersion("empty")
    }
}
