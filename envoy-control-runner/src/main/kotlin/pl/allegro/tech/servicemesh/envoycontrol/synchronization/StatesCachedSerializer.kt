package pl.allegro.tech.servicemesh.envoycontrol.synchronization

import com.google.common.cache.CacheBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import pl.allegro.tech.servicemesh.envoycontrol.model.ServicesStateProto
import java.util.concurrent.TimeUnit

class StatesCachedSerializer {

    fun get(): ServicesStateProto.ServicesState? {
        logger.info("Cache keys: ${cache.asMap().size}")
        val cacheState = cache.getIfPresent(STATES_KEY)
        logger.info("CachedMessage: $cacheState")
        return cacheState
    }

    fun serialize(state: ServicesStateProto.ServicesState) {
        try {
            logger.info("Cache keys: ${cache.asMap().size}")
            val cacheState = cache.get(STATES_KEY) { state }
            logger.info("CacheState: $cacheState")
        } catch (e: ExecutionException) {
            throw ProtoSerializerException("Error while serializing resources", e)
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(StatesCachedSerializer::class.java)
        const val STATES_KEY = "states"
        private val cache = CacheBuilder.newBuilder()
            .weakValues()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build<String, ServicesStateProto.ServicesState>()
    }

    class ProtoSerializerException
    internal constructor(message: String, cause: Throwable) : RuntimeException(message, cause)
}
