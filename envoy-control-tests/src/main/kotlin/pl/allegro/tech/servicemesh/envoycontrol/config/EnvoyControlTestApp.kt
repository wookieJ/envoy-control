package pl.allegro.tech.servicemesh.envoycontrol.config

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.pszymczyk.consul.infrastructure.Ports
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.http.HttpStatus
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControl
import pl.allegro.tech.servicemesh.envoycontrol.debug.DebugController
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.services.ServicesState
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.debug.Versions
import java.time.Duration

interface EnvoyControlTestApp {
    val appPort: Int
    val grpcPort: Int
    val appName: String
    fun run()
    fun stop()
    fun isHealthy(): Boolean
    fun getState(): ServicesState
    fun getSnapshot(nodeJson: String): SnapshotDebugResponse
    fun getGlobalSnapshot(xds: Boolean?): SnapshotDebugResponse
    fun getHealthStatus(): Health
    fun <T> bean(clazz: Class<T>): T
}

class EnvoyControlRunnerTestApp(
    val properties: Map<String, Any> = mapOf(),
    val consulPort: Int,
    val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
    override val grpcPort: Int = Ports.nextAvailable(),
    override val appPort: Int = Ports.nextAvailable()
) :
    EnvoyControlTestApp {

    override val appName = "envoy-control"
    private lateinit var app: SpringApplicationBuilder

    private val baseProperties = mapOf(
        "spring.profiles.active" to "test",
        "spring.jmx.enabled" to false,
        "envoy-control.source.consul.port" to consulPort,
        "envoy-control.envoy.snapshot.outgoing-permissions.enabled" to true,
        "envoy-control.sync.polling-interval" to Duration.ofSeconds(1).seconds,
        "envoy-control.server.port" to grpcPort,
        // Round robin gives much more predictable results in tests than LEAST_REQUEST
        "envoy-control.envoy.snapshot.load-balancing.policy" to "ROUND_ROBIN"
    )

    // TODO: remove
    fun debugMode(properties: Map<String, Any>): Map<String, Any> {
        val debugProperties = debugModeDirect()
        DebugController.setLoggerLevel(Level.DEBUG)
        return properties + debugProperties
    }

    // ALL TESTS PASSED
    fun debugModeOldSequential(): Map<String, Any> {
        DebugController.oldSequentialMode = true
        DebugController.parallelizeGetSnapshotForGroup = false
        DebugController.alternativeProtoResourcesSerializer = false

        return mapOf(
            "envoy-control.server.snapshot-send-scheduler.type" to "DIRECT",
            "envoy-control.server.snapshot-send-scheduler.parallel-pool-size" to 1
        )
    }

    // ALL TEST PASSED
    fun debugModeOldSequentialAlternativeProtoSerializer(): Map<String, Any> {
        DebugController.oldSequentialMode = true
        DebugController.parallelizeGetSnapshotForGroup = false
        DebugController.alternativeProtoResourcesSerializer = true

        return mapOf(
            "envoy-control.server.snapshot-send-scheduler.type" to "DIRECT",
            "envoy-control.server.snapshot-send-scheduler.parallel-pool-size" to 1
        )
    }

    // ALL TESTS PASSED
    fun debugModeParallel8(): Map<String, Any> {
        DebugController.oldSequentialMode = false
        DebugController.parallelizeGetSnapshotForGroup = false
        DebugController.alternativeProtoResourcesSerializer = false

        return mapOf(
            "envoy-control.server.snapshot-send-scheduler.type" to "PARALLEL",
            "envoy-control.server.snapshot-send-scheduler.parallel-pool-size" to 8
        )
    }

    // ALL TESTS PASSED
    fun debugModeDirect(): Map<String, Any> {
        DebugController.oldSequentialMode = false
        DebugController.parallelizeGetSnapshotForGroup = false
        DebugController.alternativeProtoResourcesSerializer = false

        return mapOf(
            "envoy-control.server.snapshot-send-scheduler.type" to "DIRECT",
            "envoy-control.server.snapshot-send-scheduler.parallel-pool-size" to 1
        )
    }

    override fun run() {
        app = SpringApplicationBuilder(EnvoyControl::class.java).properties(debugMode(baseProperties + properties))
        app.run("--server.port=$appPort", "-e test")
        logger.info("starting EC on port $appPort, grpc: $grpcPort, consul: $consulPort")
    }

    override fun stop() {
        app.context().close()
    }

    override fun isHealthy(): Boolean = getApplicationStatusResponse().use { it.isSuccessful }

    override fun getHealthStatus(): Health {
        val response = getApplicationStatusResponse()
        return objectMapper.readValue(response.body()?.use { it.string() }, Health::class.java)
    }

    override fun getState(): ServicesState {
        val response = httpClient
            .newCall(
                Request.Builder()
                    .get()
                    .url("http://localhost:$appPort/state")
                    .build()
            )
            .execute()
        return objectMapper.readValue(response.body()?.use { it.string() }, ServicesState::class.java)
    }

    override fun getSnapshot(nodeJson: String): SnapshotDebugResponse {
        val response = httpClient.newCall(
            Request.Builder()
                .post(RequestBody.create(MediaType.get("application/json"), nodeJson))
                .url("http://localhost:$appPort/snapshot")
                .build()
        ).execute()

        if (response.code() == HttpStatus.NOT_FOUND.value()) {
            return SnapshotDebugResponse(found = false)
        } else if (!response.isSuccessful) {
            throw SnapshotDebugResponseInvalidStatusException(response.code())
        }

        return response.body()
            ?.use { objectMapper.readValue(it.byteStream(), SnapshotDebugResponse::class.java) }
            ?.copy(found = true) ?: throw SnapshotDebugResponseMissingException()
    }

    override fun getGlobalSnapshot(xds: Boolean?): SnapshotDebugResponse {
        var url = "http://localhost:$appPort/snapshot-global"
        if (xds != null) {
            url += "?xds=$xds"
        }
        val response = httpClient.newCall(
            Request.Builder()
                .get()
                .url(url)
                .build()
        ).execute()

        if (response.code() == HttpStatus.NOT_FOUND.value()) {
            return SnapshotDebugResponse(found = false)
        } else if (!response.isSuccessful) {
            throw SnapshotDebugResponseInvalidStatusException(response.code())
        }

        return response.body()
            ?.use { objectMapper.readValue(it.byteStream(), SnapshotDebugResponse::class.java) }
            ?.copy(found = true) ?: throw SnapshotDebugResponseMissingException()
    }

    class SnapshotDebugResponseMissingException :
        RuntimeException("Expected snapshot debug in response body but got none")

    class SnapshotDebugResponseInvalidStatusException(status: Int) :
        RuntimeException("Invalid snapshot debug response status: $status")

    private fun getApplicationStatusResponse(): Response =
        httpClient
            .newCall(
                Request.Builder()
                    .get()
                    .url("http://localhost:$appPort/actuator/health")
                    .build()
            )
            .execute()

    override fun <T> bean(clazz: Class<T>): T = app.context().getBean(clazz)
        ?: throw IllegalStateException("Bean of type ${clazz.simpleName} not found in the context")

    companion object {
        val logger by logger()
        private val httpClient = OkHttpClient.Builder()
            .build()
    }
}

data class Health(
    val status: Status,
    val details: Map<String, HealthDetails>
)

data class HealthDetails(
    val status: Status
)

data class SnapshotDebugResponse(
    val found: Boolean,
    val versions: Versions? = null,
    val snapshot: ObjectNode? = null
)
