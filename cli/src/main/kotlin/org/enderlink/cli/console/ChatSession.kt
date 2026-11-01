package org.enderlink.cli.console

import org.enderlink.network.connection.Connection
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Одна терминальная сессия на процесс; стандартный ввод принадлежит CLI. */
class ChatSession {
    fun run(connection: Connection) {
        val stoppedLocally = AtomicBoolean(false)
        val inputFailure = AtomicReference<IOException?>(null)
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        println("Соединение установлено. Введите сообщение; /quit — выход.")

        // Блокирующий ввод терминала не прерывается Thread.interrupt(). Daemon
        // позволяет процессу завершиться, когда удалённая сторона отключилась.
        thread(name = "chat-console", isDaemon = true) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == "/quit") break
                    try {
                        connection.send(line.toByteArray(Charsets.UTF_8))
                    } catch (e: IllegalArgumentException) {
                        System.err.println("Сообщение не отправлено: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                inputFailure.set(e)
            } finally {
                stoppedLocally.set(true)
                try {
                    connection.close()
                } catch (e: IOException) {
                    inputFailure.compareAndSet(null, e)
                }
            }
        }

        try {
            while (true) {
                val payload = connection.receive() ?: break
                println("Собеседник: ${payload.toString(Charsets.UTF_8)}")
            }
        } catch (e: IOException) {
            if (!stoppedLocally.get()) throw e
        }
        inputFailure.get()?.let { throw it }
        println("Соединение закрыто.")
    }
}
