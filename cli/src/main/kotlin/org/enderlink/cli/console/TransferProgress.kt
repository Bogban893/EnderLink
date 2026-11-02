package org.enderlink.cli.console

/** Печатает прогресс при изменении целого процента, без строки на каждый блок. */
class TransferProgress {
    private var lastPercent = -1

    fun update(done: Long, total: Long) {
        val percent = if (total == 0L) 100 else (done.toDouble() / total * 100).toInt()
        if (percent != lastPercent) {
            println("Передача: $percent% ($done / $total байт)")
            lastPercent = percent
        }
    }
}
