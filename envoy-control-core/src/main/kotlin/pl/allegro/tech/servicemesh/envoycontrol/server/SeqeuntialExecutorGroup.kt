package pl.allegro.tech.servicemesh.envoycontrol.server

import io.envoyproxy.controlplane.server.ExecutorGroup
import pl.allegro.tech.servicemesh.envoycontrol.debug.DebugController
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger


open class SequentialExecutorGroup(
    threads: Int,
    singleThreadedExecutorFactory: (Int) -> ExecutorService
) : ExecutorGroup {

    private val executors = (0 until threads).map(singleThreadedExecutorFactory)
    private val counter = AtomicInteger(0)

    override fun next(): Executor {

        val index = counter.getAndUpdate { c ->
            val next = c+1
            if (next >= executors.size) {
                0
            } else {
                // TODO: remove
                if (next > DebugController.executorGroupLimit) {
                    0
                } else {
                    next
                }
            }
        }

        return executors[index]
    }
}
