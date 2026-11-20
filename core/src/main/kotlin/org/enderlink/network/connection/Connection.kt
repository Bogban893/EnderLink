package org.enderlink.network.connection

/**
 * Двустороннее соединение, обменивающееся целыми сообщениями.
 *
 * Правила: один читатель (`receive` из одного потока), `send` можно вызывать параллельно.
 * Ошибки чтения и записи передаются как [java.io.IOException].
 */
interface Connection : AutoCloseable {
    /** Отправляет одно сообщение целиком. */
    fun send(payload: ByteArray)

    /** Ждёт следующее сообщение. `null` означает нормальное завершение входного потока. */
    fun receive(): ByteArray?

    override fun close()
}
