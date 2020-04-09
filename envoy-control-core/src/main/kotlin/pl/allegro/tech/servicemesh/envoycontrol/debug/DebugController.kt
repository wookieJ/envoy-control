package pl.allegro.tech.servicemesh.envoycontrol.debug

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.google.protobuf.Any
import com.google.protobuf.Message
import io.envoyproxy.controlplane.server.ExecutorGroup
import io.envoyproxy.controlplane.server.serializer.ProtoResourcesSerializer
import pl.allegro.tech.servicemesh.envoycontrol.logger
import pl.allegro.tech.servicemesh.envoycontrol.snapshot.SendSnapshotScheduler
import java.lang.Thread.sleep
import java.util.Objects


open class DebugController {

    companion object {
        val logger by logger()
        var callbackOnStreamResponseDelayMs: Long = 0

        fun debug(msg: String) {
            if (logger.isDebugEnabled) {
                logger.debug(msg)
            }
        }

        fun <T> debug(msg: String, group: T) {
            if (logger.isDebugEnabled) {
                val hash = Objects.hashCode(group)
                logger.debug("$msg {group:$hash}")
            }
        }

        fun setLoggerLevel(newLevel: Level) {
            logger.let {
                if (it is Logger) {
                    it.level = newLevel
                }
            }
        }

        fun delayCallbackOnStreamResponse() {
            if (callbackOnStreamResponseDelayMs != 0L) {
                sleep(callbackOnStreamResponseDelayMs)
            }
        }


        var oldSequentialMode = false
        var sendSnapshotScheduler: SendSnapshotScheduler? = null
        var parallelizeGetSnapshotForGroup = false
        var alternativeProtoResourcesSerializer = false


        fun sendSnapshotScheduler(original: SendSnapshotScheduler): SendSnapshotScheduler {
            return sendSnapshotScheduler ?: original
        }


        // trochę nie ma sensu poniższe, bo executory są już przypisane do streamów w trakcie działania apki
        var executorGroupLimit = 1000
        var executorGroup: ExecutorGroup? = null

        fun trackExecutorGroup(original: ExecutorGroup?): ExecutorGroup {
            return ExecutorGroup {
                executorGroup?.next() ?: original?.next()
            }
        }
    }
}

class DebugProtoResourcesSerializer(
    private val defaultSerializer: ProtoResourcesSerializer,
    private val alternativeSerializer: ProtoResourcesSerializer
) : ProtoResourcesSerializer {

    override fun serialize(resources: MutableCollection<out Message>?): MutableCollection<Any> {
        return if (DebugController.alternativeProtoResourcesSerializer) {
            alternativeSerializer.serialize(resources)
        } else {
            defaultSerializer.serialize(resources)
        }
    }

    override fun serialize(resource: Message?): Any {
        return if (DebugController.alternativeProtoResourcesSerializer) {
            alternativeSerializer.serialize(resource)
        } else {
            defaultSerializer.serialize(resource)
        }
    }

}
