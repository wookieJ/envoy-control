package pl.allegro.tech.servicemesh.envoycontrol.debug

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.envoyproxy.controlplane.server.ExecutorGroup
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
