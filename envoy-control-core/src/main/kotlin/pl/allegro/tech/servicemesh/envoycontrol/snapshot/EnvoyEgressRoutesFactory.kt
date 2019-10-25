package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.TestResources
import io.envoyproxy.envoy.api.v2.RouteConfiguration
import io.envoyproxy.envoy.api.v2.core.DataSource
import io.envoyproxy.envoy.api.v2.core.HeaderValue
import io.envoyproxy.envoy.api.v2.core.HeaderValueOption
import io.envoyproxy.envoy.api.v2.route.DirectResponseAction
import io.envoyproxy.envoy.api.v2.route.QueryParameterMatcher
import io.envoyproxy.envoy.api.v2.route.Route
import io.envoyproxy.envoy.api.v2.route.RouteAction
import io.envoyproxy.envoy.api.v2.route.RouteMatch
import io.envoyproxy.envoy.api.v2.route.VirtualHost
import io.envoyproxy.envoy.type.matcher.StringMatcher

internal class EnvoyEgressRoutesFactory(
    private val properties: SnapshotProperties
) {

    /**
     * By default envoy doesn't proxy requests to provided IP address. We created cluster: envoy-original-destination
     * which allows direct calls to IP address extracted from x-envoy-original-dst-host header for calls to
     * envoy-original-destination cluster.
     */
    private val originalDestinationRoute = VirtualHost.newBuilder()
        .setName("original-destination-route")
        .addDomains("envoy-original-destination")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder()
                        .setPrefix("/")
                )
                .setRoute(
                    RouteAction.newBuilder()
                        .setCluster("envoy-original-destination")
                )
        )
        .build()

    private val wildcardRoute = VirtualHost.newBuilder()
        .setName("wildcard-route")
        .addDomains("*")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder()
                        .setPrefix("/")
                )
                .setDirectResponse(
                    DirectResponseAction.newBuilder()
                        .setStatus(properties.egress.clusterNotFoundStatusCode)
                )
        )
        .build()

    /**
     * @see TestResources.createRoute
     */
    fun createEgressRouteConfig(serviceName: String, routes: Collection<RouteSpecification>): RouteConfiguration {
        val virtualHosts = routes
            .groupBy { it.routeDomain }
            .map { (domain, routesForDomain) ->
            VirtualHost.newBuilder()
                .setName(domain)
                .addDomains(domain)
                .addAllRoutes(
                    routesForDomain.map { route ->
                        Route.newBuilder()
                            .setMatch(
                                RouteMatch.newBuilder()
                                    .setPrefix("/")
                                    .apply {
                                        route.routeTag?.let { tag ->
                                            val builder = QueryParameterMatcher.newBuilder()
                                                .setName(properties.routing.serviceTags.queryParamName)
                                            if (!tag.isEmpty()) {
                                                builder.setStringMatch(StringMatcher.newBuilder()
                                                    .setExact(tag)
                                                )
                                            }
                                            addQueryParameters(builder)
                                        }
                                    }
                            )
                            .configureAction(route)
                            .build()
                    }
                )
                .build()
        }

        return RouteConfiguration.newBuilder()
            .setName("default_routes")
            .addAllVirtualHosts(
                virtualHosts + originalDestinationRoute + wildcardRoute
            ).also {
                if (properties.incomingPermissions.enabled) {
                    it.addRequestHeadersToAdd(
                        HeaderValueOption.newBuilder()
                            .setHeader(
                                HeaderValue.newBuilder()
                                    .setKey(properties.incomingPermissions.clientIdentityHeader)
                                    .setValue(serviceName)
                            )
                    )
                }
            }
            .build()
    }

    private fun Route.Builder.configureAction(specification: RouteSpecification): Route.Builder {
        when (val action = specification.action) {
            is ClusterAction -> createClusterRoute(specification, action)
            is DirectRespAction -> createDirectResponseRoute(action)
        }.let { /* force exhaustive check at compile time */ }
        return this
    }

    private fun Route.Builder.createClusterRoute(specification: RouteSpecification, cluster: ClusterAction) {
        val routeAction = RouteAction.newBuilder()
            .setCluster(cluster.name)

        if (specification.settings.handleInternalRedirect) {
            routeAction.setInternalRedirectAction(RouteAction.InternalRedirectAction.HANDLE_INTERNAL_REDIRECT)
        }

        setRoute(routeAction)
    }

    private fun Route.Builder.createDirectResponseRoute(directResponse: DirectRespAction) {
        setDirectResponse(DirectResponseAction.newBuilder()
            .setStatus(directResponse.status)
            .setBody(DataSource.newBuilder()
                .setInlineString(directResponse.body)
            )
        )
    }
}
