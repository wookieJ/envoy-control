package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter
import org.springframework.web.client.AsyncRestTemplate
import pl.allegro.tech.discovery.consul.recipes.datacenter.ConsulDatacenterReader
import pl.allegro.tech.servicemesh.envoycontrol.EnvoyControlProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.ConsulProperties
import pl.allegro.tech.servicemesh.envoycontrol.consul.synchronization.SimpleConsulInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.AsyncControlPlaneClient
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.AsyncRestTemplateControlPlaneClient
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.ControlPlaneInstanceFetcher
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.CrossDcServiceChanges
import pl.allegro.tech.servicemesh.envoycontrol.synchronization.CrossDcServices
import okhttp3.Interceptor
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException

@Configuration
@ConditionalOnProperty(name = ["envoy-control.sync.enabled"], havingValue = "true", matchIfMissing = false)
class SynchronizationConfig {

    class LoggingInterceptor : Interceptor {
        private val logger: Logger = LoggerFactory.getLogger(SynchronizationConfig::class.java)

        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            val t1 = System.nanoTime()
            logger.info("OkHttp - " + String.format("Sending request %s on %s%n%s",
                request.url(), chain.connection(), request.headers()))

            val response = chain.proceed(request)

            val t2 = System.nanoTime()
            logger.info("OkHttp - " + String.format("Received response for %s in %.1fms%n%s",
                response.request().url(), (t2 - t1) / 1e6, response.headers()))

            return response
        }
    }

    @Bean
    fun protobufHttpMessageConverter(): ProtobufHttpMessageConverter {
        return ProtobufHttpMessageConverter()
    }

    @Bean(name = arrayOf("asyncRestTemplateProto"))
    fun asyncRestTemplateProto(
        envoyControlProperties: EnvoyControlProperties,
        httpMessageConverter: ProtobufHttpMessageConverter
    ): AsyncRestTemplate {
        val client = OkHttpClient.Builder()
            .addInterceptor(LoggingInterceptor())
            .build()
        val requestFactory = OkHttp3ClientHttpRequestFactory(client)
        requestFactory.setConnectTimeout(envoyControlProperties.sync.connectionTimeout.toMillis().toInt())
        requestFactory.setReadTimeout(envoyControlProperties.sync.readTimeout.toMillis().toInt())

        val asyncRestTemplate = AsyncRestTemplate(requestFactory)
        asyncRestTemplate.messageConverters = listOf(httpMessageConverter)
        return asyncRestTemplate
    }

    @Bean(name = arrayOf("asyncRestTemplate"))
    fun asyncRestTemplate(envoyControlProperties: EnvoyControlProperties): AsyncRestTemplate {
        val client = OkHttpClient.Builder()
            .addInterceptor(LoggingInterceptor())
            .build()
        val requestFactory = OkHttp3ClientHttpRequestFactory(client)
        requestFactory.setConnectTimeout(envoyControlProperties.sync.connectionTimeout.toMillis().toInt())
        requestFactory.setReadTimeout(envoyControlProperties.sync.readTimeout.toMillis().toInt())

        return AsyncRestTemplate(requestFactory)
    }

    @Bean
    fun controlPlaneClient(
        @Qualifier("asyncRestTemplate") asyncRestTemplate: AsyncRestTemplate,
        @Qualifier("asyncRestTemplateProto") asyncRestTemplateProto: AsyncRestTemplate,
        meterRegistry: MeterRegistry
    ) =
        AsyncRestTemplateControlPlaneClient(asyncRestTemplate, asyncRestTemplateProto, meterRegistry)

    @Bean
    fun crossDcServices(
        controlPlaneClient: AsyncControlPlaneClient,
        meterRegistry: MeterRegistry,
        controlPlaneInstanceFetcher: ControlPlaneInstanceFetcher,
        consulDatacenterReader: ConsulDatacenterReader,
        properties: EnvoyControlProperties
    ): CrossDcServiceChanges {

        val remoteDcs = consulDatacenterReader.knownDatacenters() - consulDatacenterReader.localDatacenter()
        val service = CrossDcServices(controlPlaneClient, meterRegistry, controlPlaneInstanceFetcher, remoteDcs)

        return CrossDcServiceChanges(properties, service)
    }

    @Bean
    fun instanceFetcher(
        consulProperties: ConsulProperties,
        envoyControlProperties: EnvoyControlProperties
    ) = SimpleConsulInstanceFetcher(
        ConsulClient(consulProperties.host, consulProperties.port),
        envoyControlProperties.sync.envoyControlAppName
    )
}
