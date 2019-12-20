package pl.allegro.tech.servicemesh.envoycontrol.infrastructure

import com.ecwid.consul.v1.ConsulClient
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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

@Configuration
@ConditionalOnProperty(name = ["envoy-control.sync.enabled"], havingValue = "true", matchIfMissing = false)
class SynchronizationConfig {

    @Bean
    fun protobufHttpMessageConverter(): ProtobufHttpMessageConverter {
        return ProtobufHttpMessageConverter()
    }

    @Bean(name = arrayOf("asyncRestTemplateProto"))
    fun asyncRestTemplateProto(
        envoyControlProperties: EnvoyControlProperties,
        httpMessageConverter: ProtobufHttpMessageConverter
    ): AsyncRestTemplate {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
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
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
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
