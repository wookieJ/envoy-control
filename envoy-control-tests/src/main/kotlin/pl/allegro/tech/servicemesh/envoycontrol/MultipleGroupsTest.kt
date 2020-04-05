package pl.allegro.tech.servicemesh.envoycontrol

import ch.qos.logback.classic.Level
import io.micrometer.core.instrument.Metrics.globalRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import pl.allegro.tech.servicemesh.envoycontrol.config.Ads
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsAllDependencies
import pl.allegro.tech.servicemesh.envoycontrol.config.AdsWithDisabledEndpointPermissions
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlRunnerTestApp
import pl.allegro.tech.servicemesh.envoycontrol.config.EnvoyControlTestConfiguration
import pl.allegro.tech.servicemesh.envoycontrol.config.envoy.EnvoyContainer
import pl.allegro.tech.servicemesh.envoycontrol.debug.DebugController
import pl.allegro.tech.servicemesh.envoycontrol.server.ExecutorType
import java.util.concurrent.TimeUnit


open class MultipleGroupsTest : EnvoyControlTestConfiguration() {

    data class TestSetup(
        val snapshotSendSchedulerType: ExecutorType,
        val snapshotSendSchedulerParallelPoolSize: Int,
        val onStreamResponseDelayMs: Long = 0L
    )

    companion object {
        private val logger by logger()

        val testSetupDirect = TestSetup(
            snapshotSendSchedulerType = ExecutorType.DIRECT,
            snapshotSendSchedulerParallelPoolSize = 0
        )

        val testSetupDirectOnStreamResponseDelay = TestSetup(
            snapshotSendSchedulerType = ExecutorType.DIRECT,
            snapshotSendSchedulerParallelPoolSize = 0,
            onStreamResponseDelayMs = 2000
        )

        val testSetupParallel3 = TestSetup(
            snapshotSendSchedulerType = ExecutorType.PARALLEL,
            snapshotSendSchedulerParallelPoolSize = 3
        )

        val testSetupParallel3OnStreamResponseDelay = TestSetup(
            snapshotSendSchedulerType = ExecutorType.PARALLEL,
            snapshotSendSchedulerParallelPoolSize = 3,
            onStreamResponseDelayMs = 2000
        )

        val testSetup = testSetupParallel3OnStreamResponseDelay

        protected val properties = mapOf(
            "envoy-control.envoy.snapshot.outgoing-permissions.servicesAllowedToUseWildcard" to "test-service",
            "envoy-control.server.snapshot-send-scheduler.type" to testSetup.snapshotSendSchedulerType.name,
            "envoy-control.server.snapshot-send-scheduler.parallel-pool-size" to testSetup.snapshotSendSchedulerParallelPoolSize
        )

        @JvmStatic
        @BeforeAll
        fun setupTest() {
            logger.info("Test setup: ${testSetup}")

            if (testSetup.onStreamResponseDelayMs > 0) {
                DebugController.callbackOnStreamResponseDelayMs = testSetup.onStreamResponseDelayMs
            }

            setup(
                appFactoryForEc1 = { consulPort ->
                    EnvoyControlRunnerTestApp(properties = properties, consulPort = consulPort)
                },
                envoys = 1,
                envoyConfig = Ads
            )

            DebugController.setLoggerLevel(Level.DEBUG)

            envoyContainer2 = createEnvoyContainer(
                instancesInSameDc = false,
                envoyConfig = AdsAllDependencies,
                envoyConnectGrpcPort = null,
                envoyConnectGrpcPort2 = null
            )

            envoyContainer3 = createEnvoyContainer(
                instancesInSameDc = false,
                envoyConfig = AdsWithDisabledEndpointPermissions,
                envoyConnectGrpcPort = null,
                envoyConnectGrpcPort2 = null
            )
            runCatching { envoyContainer2.start() }
                .onFailure { logger.error("Logs from failed container: ${envoyContainer2.logs}") }
                .getOrThrow()
            runCatching { envoyContainer3.start() }
                .onFailure { logger.error("Logs from failed container: ${envoyContainer3.logs}") }
                .getOrThrow()

        }

        protected lateinit var envoyContainer3: EnvoyContainer

        @JvmStatic
        @AfterAll
        fun cleanup() {
            envoyContainer2.stop()
            envoyContainer3.stop()
        }
    }

    @Test
    fun should() {

        registerService(name = "echo")

        waitForReadyServices("echo")

        untilAsserted {
            callService("echo", address = envoyContainer2.ingressListenerUrl()).also {
                assertThat(it).isOk()
            }
            callService("echo", address = envoyContainer3.ingressListenerUrl()).also {
                assertThat(it).isOk()
            }
        }

        val response = callService("echo")

        assertThat(response).isOk()

        untilAsserted {
            globalRegistry.let { listOf(it) + it.registries }
                .map { it.find("snapshot-updater.update-snapshot-for-groups.time").timer() }
                .filterNotNull().firstOrNull()
                ?.let { timer ->
                    val count = timer.count()
                    val maxMs = timer.max(TimeUnit.MILLISECONDS)
                    val avg = timer.mean(TimeUnit.MILLISECONDS)

                    assertThat(count).isGreaterThanOrEqualTo(5).describedAs("not enough timers registered")

                    logger.info("update-snapshot-for-groups TIMER COUNT: $count, MAX: ${maxMs}ms, AVG: ${avg}ms")

                }
                ?: fail("no timers!")
        }
    }
}
