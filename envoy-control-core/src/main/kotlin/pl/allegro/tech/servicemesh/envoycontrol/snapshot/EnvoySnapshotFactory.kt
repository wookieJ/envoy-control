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

        val routesSpecifications = getRoutesSpecification(serviceNames, servicesStates)
        val clusterConfigurations = routesSpecifications
            .flatMap { it.value }
            .mapNotNull { when (it.action) {
                is EdsClusterAction -> it.action
                else -> null
            } }

        val clusters = clustersFactory.getClustersForServices(clusterConfigurations, ads)

        val endpoints: List<ClusterLoadAssignment> = createLoadAssignment(servicesStates, routesSpecifications)
        val routes = listOf(
            egressRoutesFactory.createEgressRouteConfig("", routesSpecifications.values.flatten()),
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
        return SnapshotWithMetadata(snapshot, routesSpecifications)
    }

    private fun getRoutesSpecification(
        serviceNames: List<String>,
        servicesStates: List<LocalityAwareServicesState>
    ): RoutesByServiceName {

        val instancesByService = toInstancesByService(servicesStates)
        
        return if (!properties.egress.http2.enabled && !properties.routing.serviceTags.enabled) {
            serviceNames.associateWith { listOf(
                routeToEdsCluster(
                    clusterName = it, 
                    domain = it, 
                    http2Enabled = false, 
                    instances = instancesByService[it].orEmpty()
                )) }
        } else {
            instancesByService
                .mapValues { (serviceName, instances) -> toRoutesSpecification(instances, serviceName) }
        }
    }
    
    private fun toInstancesByService(servicesStates: List<LocalityAwareServicesState>): Map<String, List<InstanceWithMetadata>> {
        return servicesStates
            .flatMap { stateWithLocality ->
                stateWithLocality.servicesState.serviceNameToInstances.values
                    .map { (serviceName, instances) ->
                        val instancesWithLocality = instances
                            .map { InstanceWithMetadata(it, stateWithLocality.locality, stateWithLocality.zone) }
                        serviceName to instancesWithLocality
                    }
            }
            .groupBy({ (serviceName) -> serviceName }, { it.second })
            .mapValues { (_, instances) -> instances.flatten() }
    }

    private fun toRoutesSpecification(instances: List<InstanceWithMetadata>, serviceName: String): List<RouteSpecification> {
        val defaultRoute = toClusterRouteSpecification(instances = instances, serviceName = serviceName)
        if (properties.routing.serviceTags.enabled) {
            val allTags = instances.flatMap { it.instance.tags }.toSet()
            val routingTags = serviceTagFilter.filterTagsForRouting(allTags)

            val oneTagRoutes = routingTags.map { tag ->
                toClusterRouteSpecification(
                    instances = instances.filter { it.instance.tags.contains(tag) },
                    serviceName = serviceName,
                    clusterName = ResourceNames.edsClusterName(serviceName, tag),
                    tags = listOf(tag)
                )
            }

            val twoTagsRoutes = if (serviceTagFilter.isAllowedToMatchOnTwoTags(serviceName)) {
                routingTags
                    .flatMap { tag1 -> routingTags
                        .filter { it > tag1 }
                        .map { tag2 -> Pair(tag1, tag2) }
                    }
                    .map { (tag1, tag2) ->
                        toClusterRouteSpecification(
                            instances = instances.filter { it.instance.tags.contains(tag1) && it.instance.tags.contains(tag2) },
                            serviceName = serviceName,
                            clusterName = ResourceNames.edsClusterName(serviceName, tag1, tag2),
                            tags = listOf(tag1, tag2)
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

            return twoTagsRoutes + oneTagRoutes + tagNotFoundRoute + defaultRoute

        } else {
            return listOf(defaultRoute)
        }
    }

    private fun toClusterRouteSpecification(
        instances: List<InstanceWithMetadata>,
        serviceName: String,
        clusterName: String = serviceName,
        tags: List<String> = emptyList()
    ): RouteSpecification {

        // Http2 support is on a cluster level so if someone decides to deploy a service in dc1 with envoy and in dc2
        // without envoy then we can't set http2 because we do not know if the server in dc2 supports it.
        val allInstancesHaveEnvoyTag = { instances.isNotEmpty() && instances.all {
            it.instance.tags.contains(properties.egress.http2.tagName)
        }}

        val http2Enabled = properties.egress.http2.enabled && allInstancesHaveEnvoyTag()

        return routeToEdsCluster(
            clusterName = clusterName,
            domain = serviceName,
            http2Enabled = http2Enabled,
            tags = tags,
            instances = instances
        )
    }

    fun getSnapshotForGroup(group: Group, globalSnapshot: SnapshotWithMetadata): Snapshot {
        if (group.isGlobalGroup()) {
            return globalSnapshot.snapshot
        }
        return newSnapshotForGroup(group, globalSnapshot)
    }

    private fun getEgressRoutesSpecification(group: Group, globalSnapshot: SnapshotWithMetadata): Collection<RouteSpecification> {
        return getServiceRouteSpecifications(group, globalSnapshot) +
            getDomainRouteSpecifications(group)
    }

    private fun getDomainRouteSpecifications(group: Group): List<RouteSpecification> {
        return group.proxySettings.outgoing.getDomainDependencies().map {
            routeToDomainCluster(
                clusterName = it.getClusterName(),
                domain = it.getRouteDomain(),
                settings = it.settings
            )
        }
    }

    private fun getServiceRouteSpecifications(group: Group, globalSnapshot: SnapshotWithMetadata): Collection<RouteSpecification> {
        return when (group) {
            is ServicesGroup -> group.proxySettings.outgoing.getServiceDependencies()
                .mapNotNull { dependency ->
                    globalSnapshot.routesByService[dependency.service]?.map { it.copy(settings = dependency.settings) }
                }
                .flatten()
            is AllServicesGroup -> globalSnapshot.routesByService.values.flatten()
        }
    }

    private fun getServicesEndpointsForGroup(group: Group, globalSnapshot: SnapshotWithMetadata): List<ClusterLoadAssignment> {
        return getServiceRouteSpecifications(group, globalSnapshot)
            .mapNotNull {
                if (it.action is EdsClusterAction) globalSnapshot.snapshot.endpoints().resources().get(it.action.name)
                else null
            }
    }

    private fun newSnapshotForGroup(
        group: Group,
        globalSnapshot: SnapshotWithMetadata
    ): Snapshot {

        val clusterConfigsByService = globalSnapshot.routesByService
            .mapValues { it.value
                .mapNotNull { if (it.action is EdsClusterAction) it.action else null }
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
        serviceInstances: List<InstanceWithMetadata>,
        zone: String,
        priority: Int
    ): LocalityLbEndpoints =
        LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder().setZone(zone).build())
            .addAllLbEndpoints(serviceInstances.map { createLbEndpoint(it.instance) })
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

        return setMetadata(Metadata.newBuilder().putFilterMetadata("envoy.lb", metadataKeys.build()))
    }

    private fun LbEndpoint.Builder.setLoadBalancingWeightFromInstance(instance: ServiceInstance): LbEndpoint.Builder =
        when (properties.loadBalancing.weights.enabled) {
            true -> setLoadBalancingWeight(UInt32Value.of(instance.weight))
            false -> this
        }

    private fun toEnvoyPriority(locality: LocalityEnum): Int = if (locality == LocalityEnum.LOCAL) 0 else 1
    
    
    private fun createLoadAssignment(
        localityAwareServicesStates: List<LocalityAwareServicesState>,
        routesSpecifications: RoutesByServiceName
    ): List<ClusterLoadAssignment> {
        return localityAwareServicesStates
            .flatMap {
                val locality = it.locality
                val zone = it.zone

                routesSpecifications.flatMap { (_, routes) -> routes
                    .mapNotNull { when (it.action) {
                        is EdsClusterAction -> it.action
                        else -> null
                    }}
                    .map {
                        it.name to createEndpointsGroup(
                            serviceInstances = it.instances.filterByLocality(locality, zone),
                            zone = zone,
                            priority = toEnvoyPriority(locality)
                        )
                    }
                }
            }
            .groupBy ({ (clusterName) -> clusterName }, { (_, endpointGroups) -> endpointGroups })
            .map { (clusterName, endpointsGroups) ->
                ClusterLoadAssignment.newBuilder()
                    .setClusterName(clusterName)
                    .addAllEndpoints(endpointsGroups)
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

    private fun routeToEdsCluster(
        clusterName: String,
        domain: String,
        http2Enabled: Boolean,
        instances: List<InstanceWithMetadata>,
        tags: List<String> = emptyList()
    ): RouteSpecification {
        return RouteSpecification(
            name = clusterName,
            routeDomain = domain,
            settings = defaultDependencySettings,
            action = EdsClusterAction(name = clusterName, http2Enabled = http2Enabled, instances = instances),
            routeTag = tags.joinToString(",")
        )
    }
    
    private fun routeToDomainCluster(
        clusterName: String,
        domain: String,
        settings: DependencySettings
    ): RouteSpecification {
        return RouteSpecification(
            name = clusterName,
            routeDomain = domain,
            settings = settings,
            action = StrictDnsClusterAction(name = clusterName, http2Enabled = false)
        )
    }

    private fun routeDirectResponse(name: String, domain: String, body: String, status: Int, matchOnlyOnAnyTag: Boolean = false): RouteSpecification {
        return RouteSpecification(
            name = name,
            routeDomain = domain,
            routeTag = if (matchOnlyOnAnyTag) "" else null,
            settings = DependencySettings(),
            action = DirectRespAction(body, status)
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

sealed class RouteAction

internal data class DirectRespAction(val body: String, val status: Int): RouteAction()
internal sealed class ClusterAction : RouteAction() {
    abstract val name: String
    abstract val http2Enabled: Boolean
}
internal data class EdsClusterAction(
    override val name: String,
    override val http2Enabled: Boolean,
    val instances: List<InstanceWithMetadata>
): ClusterAction()

internal data class StrictDnsClusterAction(
    override val name: String,
    override val http2Enabled: Boolean
) : ClusterAction()

internal data class RouteSpecification(
    val name: String,
    val routeDomain: String,
    val settings: DependencySettings,
    val action: RouteAction,
    val routeTag: String? = null
)

internal typealias RoutesByServiceName = Map<String, List<RouteSpecification>>


