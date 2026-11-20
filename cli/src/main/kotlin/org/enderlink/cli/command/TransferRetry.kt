package org.enderlink.cli.command

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/** Повторяем только разрыв транспорта; ошибки проверки данных остаются терминальными. */
internal fun <T> retryTransfer(retries: Int, receiving: Boolean, action: (Int) -> T): T {
    for (attempt in 0..retries) {
        try {
            return action(attempt)
        } catch (e: IOException) {
            if (!isConnectionFailure(e)) throw e
            if (attempt == retries) {
                throw IOException("Соединение прервано. Исчерпано попыток: ${retries + 1}. Запустите те же команды снова для докачки.", e)
            }
            println("Соединение прервано: ${e.message}. Повтор ${attempt + 1} из $retries…")
            if (!receiving) {
                try { Thread.sleep(2_000) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Передача отменена", interrupted)
                }
            }
        }
    }
    error("unreachable")
}

private fun isConnectionFailure(error: IOException): Boolean =
    error is EOFException || error is SocketException || error is SocketTimeoutException ||
        error.message?.startsWith("Соединение iroh прервано") == true ||
        error.message?.startsWith("Ошибка iroh:") == true ||
        error.message == "Соединение закрыто"
