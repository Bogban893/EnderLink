package org.enderlink.network.connection

/** Сетевой адрес участника. */
data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "Адрес не должен быть пустым" } // host must not be blank
        require(port in 1..65535) { "Порт должен быть в диапазоне 1..65535: $port" }
    }
}
