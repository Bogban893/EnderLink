package org.enderlink.network.connection

/** Общий интерфейс соединения; операции обмена будут добавлены на следующем этапе. */
interface Connection : AutoCloseable {
    fun send(payload: ByteArray)
    fun receive(): ByteArray?
    override fun close()
}
