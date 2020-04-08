package pl.allegro.tech.servicemesh.envoycontrol.snapshot

import io.envoyproxy.controlplane.cache.Snapshot
import io.envoyproxy.controlplane.cache.SnapshotCache
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import pl.allegro.tech.servicemesh.envoycontrol.debug.DebugController
import pl.allegro.tech.servicemesh.envoycontrol.debug.DebugController.Companion.debug
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.ADS
import pl.allegro.tech.servicemesh.envoycontrol.groups.CommunicationMode.XDS
import pl.allegro.tech.servicemesh.envoycontrol.groups.Group
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.LocalityAwareServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.EnvoyListenersFactory
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.listeners.filters.EnvoyHttpFilters
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.routing.ServiceTagMetadataGenerator
import pl.allegro.tech.servicemesh.envoycontrol.utils.measureBuffer
import pl.allegro.tech.servicemesh.envoycontrol.utils.onBackpressureLatestMeasured
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

class SnapshotUpdater(
    private val cache: SnapshotCache<Group>,
    private val properties: SnapshotProperties,
    private val updateSnapshotScheduler: Scheduler,
    private val sendSnapshotScheduler: SendSnapshotScheduler,
    private val onGroupAdded: Flux<out List<Group>>,
    private val meterRegistry: MeterRegistry,
    envoyHttpFilters: EnvoyHttpFilters = EnvoyHttpFilters.emptyFilters,
    serviceTagFilter: ServiceTagMetadataGenerator = ServiceTagMetadataGenerator(properties.routing.serviceTags)
) {
    companion object {
        private val logger by logger()
    }

    private val versions = SnapshotsVersions()
    private val snapshotFactory = EnvoySnapshotFactory(
        ingressRoutesFactory = EnvoyIngressRoutesFactory(properties),
        egressRoutesFactory = EnvoyEgressRoutesFactory(properties),
        clustersFactory = EnvoyClustersFactory(properties),
        listenersFactory = EnvoyListenersFactory(
                properties,
                envoyHttpFilters
        ),
        // Remember when LDS change we have to send RDS again
        snapshotsVersions = versions,
        properties = properties,
        meterRegistry = meterRegistry,
        serviceTagFilter = serviceTagFilter
    )

    private var globalSnapshot: UpdateResult? = null

    fun getGlobalSnapshot(): UpdateResult? {
        return globalSnapshot
    }

    fun start(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return Flux.merge(
                1, // prefetch 1, instead of default 32, to avoid processing stale items in case of backpressure
                services(changes).subscribeOn(updateSnapshotScheduler),
                groups().subscribeOn(updateSnapshotScheduler)
        )
                .measureBuffer("snapshot-updater-merged", meterRegistry, innerSources = 2)
                .checkpoint("snapshot-updater-merged")
                .name("snapshot-updater-merged").metrics()
                .scan { previous: UpdateResult, newUpdate: UpdateResult ->
                    UpdateResult(
                            action = newUpdate.action,
                            groups = newUpdate.groups,
                            adsSnapshot = newUpdate.adsSnapshot ?: previous.adsSnapshot,
                            xdsSnapshot = newUpdate.xdsSnapshot ?: previous.xdsSnapshot
                    )
                }
                // concat map guarantees sequential processing (unlike flatMap)
                .concatMap { result ->
                    val groups = if (result.action == Action.ALL_SERVICES_GROUP_ADDED) {
                        cache.groups()
                    } else {
                        result.groups
                    }
                    if (result.adsSnapshot != null || result.xdsSnapshot != null) {
                        if (DebugController.oldSequentialMode) {
                            updateSnapshotForGroupsOldSequential(groups, result)
                            Mono.just(result)
                        } else {
                            updateSnapshotForGroups(groups, result)
                        }
                    } else {
                        Mono.empty()
                    }
                }
    }

    fun groups(): Flux<UpdateResult> {
        // see GroupChangeWatcher
        return onGroupAdded
                .publishOn(updateSnapshotScheduler)
                .measureBuffer("snapshot-updater-groups-published", meterRegistry)
                .checkpoint("snapshot-updater-groups-published")
                .name("snapshot-updater-groups-published").metrics()
                .map { groups ->
                    UpdateResult(action = Action.SERVICES_GROUP_ADDED, groups = groups)
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.groups.updates.errors").increment()
                    logger.error("Unable to process new group", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ERROR_PROCESSING_CHANGES))
                }
    }

    fun services(changes: Flux<List<LocalityAwareServicesState>>): Flux<UpdateResult> {
        return changes
                .sample(properties.stateSampleDuration)
                .name("snapshot-updater-services-sampled").metrics()
                .onBackpressureLatestMeasured("snapshot-updater-services-sampled", meterRegistry)
                // prefetch = 1, instead of default 256, to avoid processing stale states in case of backpressure
                .publishOn(updateSnapshotScheduler, 1)
                .measureBuffer("snapshot-updater-services-published", meterRegistry)
                .checkpoint("snapshot-updater-services-published")
                .name("snapshot-updater-services-published").metrics()
                .createClusterConfigurations()
                .map { (states, clusters) ->
                    var lastXdsSnapshot: GlobalSnapshot? = null
                    var lastAdsSnapshot: GlobalSnapshot? = null

                    if (properties.enabledCommunicationModes.xds) {
                        lastXdsSnapshot = snapshotFactory.newSnapshot(states, clusters, XDS)
                    }
                    if (properties.enabledCommunicationModes.ads) {
                        lastAdsSnapshot = snapshotFactory.newSnapshot(states, clusters, ADS)
                    }

                    val updateResult = UpdateResult(
                            action = Action.ALL_SERVICES_GROUP_ADDED,
                            adsSnapshot = lastAdsSnapshot,
                            xdsSnapshot = lastXdsSnapshot
                    )
                    globalSnapshot = updateResult
                    updateResult
                }
                .onErrorResume { e ->
                    meterRegistry.counter("snapshot-updater.services.updates.errors").increment()
                    logger.error("Unable to process service changes", e)
                    Mono.justOrEmpty(UpdateResult(action = Action.ERROR_PROCESSING_CHANGES))
                }
    }

    private fun updateSnapshotForGroup(group: Group, groupSnapshot: Snapshot): Boolean = try {
        debug("updateSnapshotForGroup: STARTED") // TODO: remove
        val setSnapshot = { cache.setSnapshot(group, groupSnapshot) }
        if (properties.metrics.cacheSetSnapshotEnabled) {
            meterRegistry.timer("snapshot-updater.set-snapshot.${group.serviceName}.time").record(setSnapshot)
        } else {
            setSnapshot()
        }
        debug("updateSnapshotForGroup: ENDED") // TODO: remove
        true
    } catch (e: Throwable) {
        meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
        logger.error("Unable to create snapshot for group ${group.serviceName}", e)
        false
    }

    private fun updateSnapshotForGroups(groups: Collection<Group>, result: UpdateResult): Mono<UpdateResult> {
        debug("updateSnapshotForGroups: STARTED") // TODO: remove
        val timer = Timer.start()
        versions.retainGroups(cache.groups())

        val sendSnapshotScheduler = DebugController.sendSnapshotScheduler(sendSnapshotScheduler)  // TODO: remove

        val sendResults = when (sendSnapshotScheduler) {
            is DirectSendSnapshotScheduler -> {
                val snapshots = groups.mapNotNull { group ->
                    getSnapshotForGroup(group, result)?.let { (group to it) }
                }
                Flux.fromIterable(snapshots)
                    .map { (group, snapshot) -> updateSnapshotForGroup(group, snapshot) }
            }
            // TODO: test with multiple envoys (groups)
            is ParallelSendSnapshotScheduler -> {
                if (DebugController.parallelizeGetSnapshotForGroup) {
                    Flux.fromIterable(groups)
                        .parallel(sendSnapshotScheduler.parallelism)
                        .runOn(sendSnapshotScheduler.scheduler)
                        .map { group ->
                            getSnapshotForGroup(group, result)
                                ?.let { updateSnapshotForGroup(group, it) }
                                ?: false
                        }
                        .sequential()
                } else {
                    val snapshots = groups.mapNotNull { group ->
                        getSnapshotForGroup(group, result)?.let { (group to it) }
                    }
                    Flux.fromIterable(snapshots)
                        .parallel(sendSnapshotScheduler.parallelism)
                        .runOn(sendSnapshotScheduler.scheduler)
                        .map { (group, snapshot) -> updateSnapshotForGroup(group, snapshot) }
                        .sequential()
                }
            }
        }

        return sendResults.then(Mono.fromCallable {
            timer.stop(meterRegistry.timer("snapshot-updater.update-snapshot-for-groups.time"))
            debug("updateSnapshotForGroups: ENDED") // TODO: remove
            result
        })
    }

    // TODO: remove
    private fun updateSnapshotForGroupOldSequential(group: Group, globalSnapshot: GlobalSnapshot) {
        try {
            val groupSnapshot = snapshotFactory.getSnapshotForGroup(group, globalSnapshot)
            val setSnapshot = { cache.setSnapshot(group, groupSnapshot) }
            if (properties.metrics.cacheSetSnapshotEnabled) {
                meterRegistry.timer("snapshot-updater.set-snapshot.${group.serviceName}.time").record(setSnapshot)
            } else {
                setSnapshot()
            }
        } catch (e: Throwable) {
            meterRegistry.counter("snapshot-updater.services.${group.serviceName}.updates.errors").increment()
            logger.error("Unable to create snapshot for group ${group.serviceName}", e)
        }
    }

    // TODO: remove
    private fun updateSnapshotForGroupsOldSequential(groups: Collection<Group>, result: UpdateResult) = meterRegistry
        .timer("snapshot-updater.update-snapshot-for-groups.time").record {
            debug("updateSnapshotForGroupsOLD: STARTED") // TODO: remove
            versions.retainGroups(cache.groups())
            groups.forEach { group ->
                if (result.adsSnapshot != null && group.communicationMode == ADS) {
                    updateSnapshotForGroupOldSequential(group, result.adsSnapshot)
                } else if (result.xdsSnapshot != null && group.communicationMode == XDS) {
                    updateSnapshotForGroupOldSequential(group, result.xdsSnapshot)
                } else {
                    meterRegistry.counter("snapshot-updater.communication-mode.errors").increment()
                    logger.error("Requested snapshot for ${group.communicationMode.name} mode, but it is not here. " +
                        "Handling Envoy with not supported communication mode should have been rejected before." +
                        " Please report this to EC developers.")
                }
            }
            debug("updateSnapshotForGroupsOLD: ENDED") // TODO: remove
        }

    private fun getSnapshotForGroup(group: Group, result: UpdateResult): Snapshot? {
        return if (result.adsSnapshot != null && group.communicationMode == ADS) {
            snapshotFactory.getSnapshotForGroup(group, result.adsSnapshot)
        } else if (result.xdsSnapshot != null && group.communicationMode == XDS) {
            snapshotFactory.getSnapshotForGroup(group, result.xdsSnapshot)
        } else {
            meterRegistry.counter("snapshot-updater.communication-mode.errors").increment()
            logger.error("Requested snapshot for ${group.communicationMode.name} mode, but it is not here. " +
                "Handling Envoy with not supported communication mode should have been rejected before." +
                " Please report this to EC developers.")
            null
        }
    }

    private fun Flux<List<LocalityAwareServicesState>>.createClusterConfigurations(): Flux<StatesAndClusters> = this
        .scan(StatesAndClusters.initial) { previous, currentStates -> StatesAndClusters(
            states = currentStates,
            clusters = snapshotFactory.clusterConfigurations(currentStates, previous.clusters)
        ) }
        .filter { it !== StatesAndClusters.initial }

    private data class StatesAndClusters(
        val states: List<LocalityAwareServicesState>,
        val clusters: Map<String, ClusterConfiguration>
    ) {
        companion object {
            val initial = StatesAndClusters(emptyList(), emptyMap())
        }
    }
}

enum class Action {
    SERVICES_GROUP_ADDED, ALL_SERVICES_GROUP_ADDED, ERROR_PROCESSING_CHANGES
}

class UpdateResult(
    val action: Action,
    val groups: List<Group> = listOf(),
    val adsSnapshot: GlobalSnapshot? = null,
    val xdsSnapshot: GlobalSnapshot? = null
)

sealed class SendSnapshotScheduler
object DirectSendSnapshotScheduler : SendSnapshotScheduler()
data class ParallelSendSnapshotScheduler(
    val scheduler: Scheduler,
    val parallelism: Int
) : SendSnapshotScheduler()
