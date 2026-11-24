package org.enderlink.coordinator

/** Run behind an HTTPS reverse proxy; the service itself listens on loopback only. */
fun main() {
    val port = System.getenv("ENDERLINK_COORDINATOR_PORT")?.toIntOrNull() ?: 28770
    require(port in 1..65535) { "Некорректный ENDERLINK_COORDINATOR_PORT" }
    CoordinatorService.open(port = port).use { service ->
        service.start()
        println("EnderLink coordinator listening on 127.0.0.1:${service.port}")
        Thread.currentThread().join()
    }
}
