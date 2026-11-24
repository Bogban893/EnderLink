package org.enderlink.agent

/** Foreground process for a system service manager; no stdin or self-daemonization. */
object HostService {
    fun run(server: VanillaServer, command: List<String>) {
        val lock = Any()
        var finished = false
        fun finish() = synchronized(lock) {
            if (!finished) {
                val version = if (server.isRunning()) server.stopAndPublish() else server.publishStopped()
                finished = true
                println("Сервер штатно остановлен. Версия опубликована: $version")
            }
        }
        val shutdown = Thread({
            try { finish() }
            catch (error: Exception) {
                System.err.println("Не удалось штатно завершить сервер: ${error.message}. Мир: ${server.worldDirectory}")
            }
        }, "enderlink-host-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            server.start(command)
            println("Сервер работает без консоли: ${server.status()}")
            while (server.isRunning()) Thread.sleep(500)
            finish()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }
}
