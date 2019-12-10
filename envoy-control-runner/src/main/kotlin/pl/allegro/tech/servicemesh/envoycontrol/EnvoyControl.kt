package pl.allegro.tech.servicemesh.envoycontrol

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter

@SpringBootApplication
class EnvoyControl(
    val controlPlane: ControlPlane
) : CommandLineRunner {

    override fun run(vararg args: String?) {
        controlPlane.start()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(EnvoyControl::class.java, *args)
        }
    }

    @Bean
    fun protobufHttpMessageConverter(): ProtobufHttpMessageConverter {
        return ProtobufHttpMessageConverter()
    }
}
