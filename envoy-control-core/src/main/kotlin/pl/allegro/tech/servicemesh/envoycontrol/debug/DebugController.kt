package pl.allegro.tech.servicemesh.envoycontrol.debug

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import pl.allegro.tech.servicemesh.envoycontrol.logger
import java.lang.Thread.sleep
import java.util.Objects


open class DebugController {

    companion object {
        val logger by logger()
        var callbackOnStreamResponseDelayMs: Long = 0
        var oldSequentialMode = false

        var executorGroupLimit = 1000

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
    }

}
