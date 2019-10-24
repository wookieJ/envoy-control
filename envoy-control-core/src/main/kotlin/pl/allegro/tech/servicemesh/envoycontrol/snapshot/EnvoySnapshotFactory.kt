package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import com.google.protobuf.Struct
import com.google.protobuf.UInt32Value
import com.google.protobuf.Value
import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.envoy.api.v2.Cluster
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment
import io.envoyproxy.envoy.api.v2.Listener
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.auth.Secret
import io.envoyproxy.envoy.api.v2.core.Address
import io.envoyproxy.envoy.api.v2.core.Locality
import io.envoyproxy.envoy.api.v2.core.Metadata
import io.envoyproxy.envoy.api.v2.core.SocketAddress
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints
import pl.allegro.tech.servicemesh.envoycontrol.groups.AllServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.groups.DependencySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.groups.ProxySettings
import pl.allegro.tech.servicemesh.envoycontrol.groups.ServicesGroup
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstance
import pl.allegro.tech.servicemesh.envoycontrol.services.ServiceInstances
import pl.allegro.tech.servicemesh.envoycontrol.services.Locality as LocalityEnum

internal class EnvoySnapshotFactory(
    private val ingressRoutesFactory: EnvoyIngressRoutesFactory,
    private val egressRoutesFactory: EnvoyEgressRoutesFactory,
    private val clustersFactory: EnvoyClustersFactory,
    private val snapshotsVersions: SnapshotsVersions,
    private val properties: SnapshotProperties,
    private val serviceTagFilter: ServiceTagFilter = DefaultServiceTagFilter(),
    private val defaultDependencySettings: DependencySettings =
        DependencySettings(properties.egress.handleInternalRedirect)
) {
    private val instanceWithTagNotFoundMsg = "There is no instance with specified service-tag"

    fun newSnapshot(servicesStates: List<LocalityAwareServicesState>, ads: Boolean): SnapshotWithMetadata {
        val serviceNames = servicesStates.flatMap { it.servicesState.serviceNames() }.distinct()

        val routeSpecifications = getRoutesSpecification(serviceNames, servicesStates)
        val clusterConfigurations = routeSpecifications
            .flatMap { it.value }
            .mapNotNull { when (it.action) {
                is ClusterConfiguration -> it.action
                else -> null
            } }

        val clusters = clustersFactory.getClustersForServices(clusterConfigurations, ads)

        val endpoints: List<ClusterLoadAssignment> = createLoadAssignment(servicesStates)
        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig("", routeSpecifications.values.flatten()),
            ingressRoutesFactory.createSecuredIngressRouteConfig(ProxySettings())
        )

        val version = snapshotsVersions.version(AllServicesGroup(ads), clusters, endpoints)

        val snapshot = createSnapshot(
            clusters = clusters,
            clustersVersion = version.clusters,
            endpoints = endpoints,
            endpointsVersions = version.endpoints,
            routes = routes,
            routesVersion = RoutesVersion(version.clusters.value)
        )
        return SnapshotWithMetadata(snapshot, routeSpecifications)
    }

    private fun getRoutesSpecification(
        serviceNames: List<String>,
        servicesStates: List<LocalityAwareServicesState>
    ): Map<String, List<RouteSpecification>> {

        return if (!properties.egress.http2.enabled && !properties.routing.serviceTags.enabled) {
            serviceNames.associateWith { listOf(routeToEdsCluster(clusterName = it, domain = it, http2Enabled = false)) }
        } else {
            servicesStates.flatMap {
                it.servicesState.serviceNameToInstances.values
            }.groupBy {
                it.serviceName
            }.mapValues { (serviceName, instances) ->
                toRoutesSpecification(instances.flatMap { it.instances }, serviceName)
            }
        }
    }

    private fun toRoutesSpecification(instances: List<ServiceInstance>, serviceName: String): List<RouteSpecification> {
        val defaultRoute = toClusterRouteSpecification(instances = instances, serviceName = serviceName)
        if (properties.routing.serviceTags.enabled) {
            val allTags = instances.flatMap { it.tags }.toSet()
            val routingTags = serviceTagFilter.filterTagsForRouting(allTags)

            val oneTagClusters = routingTags.map { tag ->
                toClusterRouteSpecification(
                    instances = instances.filter { it.tags.contains(tag) },
                    serviceName = serviceName,
                    name = ResourceNames.edsClusterName(serviceName, tag)
                )
            }

            val twoTagsClusters = if (serviceTagFilter.isAllowedToMatchOnTwoTags(serviceName)) {
                routingTags
                    .flatMap { tag1 -> routingTags
                        .filter { it > tag1 }
                        .map { tag2 -> Pair(tag1, tag2) }
                    }
                    .map { (tag1, tag2) ->
                        toClusterRouteSpecification(
                            instances = instances.filter { it.tags.contains(tag1) && it.tags.contains(tag2) },
                            serviceName = ResourceNames.edsClusterName(serviceName, tag1, tag2)
                        )
                    }
            } else {
                emptyList()
            }

            val tagNotFoundRoute = routeDirectResponse(
                name = ResourceNames.tagsNotFoundRouteName(serviceName),
                domain = serviceName,
                body = instanceWithTagNotFoundMsg,
                status = 503,
                matchOnlyOnAnyTag = true
            )

            return twoTagsClusters + oneTagClusters + tagNotFoundRoute + defaultRoute

        } else {
            return listOf(defaultRoute)
        }
    }

    private fun toClusterRouteSpecification(instances: List<ServiceInstance>, serviceName: String, name: String? = null): RouteSpecification {

        // Http2 support is on a cluster level so if someone decides to deploy a service in dc1 with envoy and in dc2
        // without envoy then we can't set http2 because we do not know if the server in dc2 supports it.
        val allInstancesHaveEnvoyTag = { instances.isNotEmpty() && instances.all {
            it.tags.contains(properties.egress.http2.tagName)
        }}

        val http2Enabled = properties.egress.http2.enabled && allInstancesHaveEnvoyTag()

        return routeToEdsCluster(clusterName = name ?: serviceName, domain = serviceName, http2Enabled = http2Enabled)
    }

    fun getSnapshotForGroup(group: Group, globalSnapshot: SnapshotWithMetadata): Snapshot {
        if (group.isGlobalGroup()) {
            return globalSnapshot.snapshot
        }
        return newSnapshotForGroup(group, globalSnapshot)
    }

    private fun getEgressRoutesSpecification(group: Group, globalSnapshot: Snapshot): Collection<RouteSpecification> {
        return getServiceRouteSpecifications(group, globalSnapshot) +
            getDomainRouteSpecifications(group)
    }

    private fun getDomainRouteSpecifications(group: Group): List<RouteSpecification> {
        return group.proxySettings.outgoing.getDomainDependencies().map {
            RouteSpecification(
                clusterName = it.getClusterName(),
                routeDomain = it.getRouteDomain(),
                settings = it.settings
            )
        }
    }

    private fun getServiceRouteSpecifications(group: Group, globalSnapshot: Snapshot): Collection<RouteSpecification> {
        return when (group) {
            is ServicesGroup -> group.proxySettings.outgoing.getServiceDependencies().map {
                RouteSpecification(
                    clusterName = it.service,
                    routeDomain = it.service,
                    settings = it.settings
                )
            }
            is AllServicesGroup -> globalSnapshot.clusters().resources().map {
                RouteSpecification(
                    clusterName = it.key,
                    routeDomain = it.key,
                    settings = defaultDependencySettings
                )
            }
        }
    }

    private fun getServicesEndpointsForGroup(group: Group, globalSnapshot: Snapshot): List<ClusterLoadAssignment> {
        return getServiceRouteSpecifications(group, globalSnapshot)
            .mapNotNull { globalSnapshot.endpoints().resources().get(it.clusterName) }
    }

    private fun newSnapshotForGroup(
        group: Group,
        globalSnapshot: SnapshotWithMetadata
    ): Snapshot {

        val clusterConfigsByService = globalSnapshot.routesByService
            .mapValues { it.value
                .mapNotNull { if (it.action is ClusterConfiguration) it.action else null }
            }

        val clusters: List<Cluster> =
            clustersFactory.getClustersForGroup(group, globalSnapshot.snapshot, clusterConfigsByService)

        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig(
                group.serviceName, getEgressRoutesSpecification(group, globalSnapshot)
            ),
            ingressRoutesFactory.createSecuredIngressRouteConfig(group.proxySettings)
        )

        if (clusters.isEmpty()) {
            return createSnapshot(routes = routes)
        }

        val endpoints = getServicesEndpointsForGroup(group, globalSnapshot)

        val version = snapshotsVersions.version(group, clusters, endpoints)

        return createSnapshot(
            clusters = clusters,
            clustersVersion = version.clusters,
            endpoints = endpoints,
            endpointsVersions = version.endpoints,
            routes = routes,
            // we assume, that routes don't change during Envoy lifecycle unless clusters change
            routesVersion = RoutesVersion(version.clusters.value)
        )
    }

    private fun createEndpointsGroup(
        serviceInstances: ServiceInstances,
        zone: String,
        priority: Int
    ): LocalityLbEndpoints =
        LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder().setZone(zone).build())
            .addAllLbEndpoints(serviceInstances.instances.map { createLbEndpoint(it) })
            .setPriority(priority)
            .build()

    private fun createLbEndpoint(serviceInstance: ServiceInstance): LbEndpoint {
        return LbEndpoint.newBuilder()
            .setEndpoint(
                buildEndpoint(serviceInstance)
            )
            .setMetadata(serviceInstance)
            .setLoadBalancingWeightFromInstance(serviceInstance)
            .build()
    }

    private fun buildEndpoint(serviceInstance: ServiceInstance): Endpoint.Builder {
        return Endpoint.newBuilder()
            .setAddress(
                buildAddress(serviceInstance)
            )
    }

    private fun buildAddress(serviceInstance: ServiceInstance): Address.Builder {
        return Address.newBuilder()
            .setSocketAddress(
                buildSocketAddress(serviceInstance)
            )
    }

    private fun buildSocketAddress(serviceInstance: ServiceInstance): SocketAddress.Builder {
        return SocketAddress.newBuilder()
            .setAddress(serviceInstance.address)
            .setPortValue(serviceInstance.port)
            .setProtocol(SocketAddress.Protocol.TCP)
    }

    private fun LbEndpoint.Builder.setMetadata(instance: ServiceInstance): LbEndpoint.Builder {
        val metadataKeys = Struct.newBuilder()

        if (properties.loadBalancing.canary.enabled && instance.canary) {
            metadataKeys.putFields(
                properties.loadBalancing.canary.metadataKey,
                Value.newBuilder().setStringValue(properties.loadBalancing.canary.headerValue).build()
            )
        }
        if (instance.regular) {
            metadataKeys.putFields(
                properties.loadBalancing.regularMetadataKey,
                Value.newBuilder().setBoolValue(true).build()
            )
        }

        if (properties.routing.serviceTags.enabled) {
            addServiceTagsToMetadata(metadataKeys, instance)
        }

        return setMetadata(Metadata.newBuilder().putFilterMetadata("envoy.lb", metadataKeys.build()))
    }

    private fun LbEndpoint.Builder.setLoadBalancingWeightFromInstance(instance: ServiceInstance): LbEndpoint.Builder =
        when (properties.loadBalancing.weights.enabled) {
            true -> setLoadBalancingWeight(UInt32Value.of(instance.weight))
            false -> this
        }

    private fun toEnvoyPriority(locality: LocalityEnum): Int = if (locality == LocalityEnum.LOCAL) 0 else 1

    private fun createLoadAssignment(
        localityAwareServicesStates: List<LocalityAwareServicesState>
    ): List<ClusterLoadAssignment> {
        return localityAwareServicesStates
            .flatMap {
                val locality = it.locality
                val zone = it.zone

                it.servicesState.serviceNameToInstances.map { (serviceName, serviceInstances) ->
                    serviceName to createEndpointsGroup(serviceInstances, zone, toEnvoyPriority(locality))
                }
            }
            .groupBy { (serviceName) ->
                serviceName
            }
            .map { (serviceName, serviceNameLocalityLbEndpointsPairs) ->
                val localityLbEndpoints = serviceNameLocalityLbEndpointsPairs.map { (_, localityLbEndpoint) ->
                    localityLbEndpoint
                }

                ClusterLoadAssignment.newBuilder()
                    .setClusterName(serviceName)
                    .addAllEndpoints(localityLbEndpoints)
                    .build()
            }
    }

    private fun createSnapshot(
        clusters: List<Cluster> = emptyList(),
        clustersVersion: ClustersVersion = ClustersVersion.EMPTY_VERSION,
        endpoints: List<ClusterLoadAssignment> = emptyList(),
        endpointsVersions: EndpointsVersion = EndpointsVersion.EMPTY_VERSION,
        routes: List<RouteConfiguration> = emptyList(),
        routesVersion: RoutesVersion = RoutesVersion.EMPTY_VERSION
    ): Snapshot =
        Snapshot.create(
            clusters,
            clustersVersion.value,
            endpoints,
            endpointsVersions.value,
            emptyList<Listener>(),
            ListenersVersion.EMPTY_VERSION.value,
            routes,
            routesVersion.value,
            emptyList<Secret>(),
            SecretsVersion.EMPTY_VERSION.value
        )

    private fun routeToEdsCluster(clusterName: String, domain: String, http2Enabled: Boolean): RouteSpecification {
        return RouteSpecification(
            name = clusterName,
            routeDomain = domain,
            settings = defaultDependencySettings,
            action = ClusterConfiguration(name = clusterName, http2Enabled = http2Enabled)
        )
    }

    private fun routeDirectResponse(name: String, domain: String, body: String, status: Int, matchOnlyOnAnyTag: Boolean = false): RouteSpecification {
        return RouteSpecification(
            name = name,
            routeDomain = domain,
            routeTag = if (matchOnlyOnAnyTag) "" else null,
            settings = DependencySettings(),
            action = DirectResponseConfiguration(body, status)
        )
    }
}

object ResourceNames {
    fun edsClusterName(serviceName: String, vararg tags: String): String {
        return if (tags.isEmpty()) {
            serviceName
        } else {
            serviceName + tags.joinToString(separator = "][", prefix = "[", postfix = "]")
        }
    }

    fun tagsNotFoundRouteName(serviceName: String): String {
        return "${serviceName}_tag_not_found"
    }
}

sealed class RouteActionConfiguration

internal data class DirectResponseConfiguration(val body: String, val status: Int): RouteActionConfiguration()

internal data class ClusterConfiguration(val name: String, val http2Enabled: Boolean): RouteActionConfiguration()

internal data class RouteSpecification(
    val name: String,
    val routeDomain: String,
    val settings: DependencySettings,
    val action: RouteActionConfiguration,
    val routeTag: String? = null
)


