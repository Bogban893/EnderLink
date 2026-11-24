package org.enderlink.cli.console

/** Печатает прогресс при изменении целого процента, без строки на каждый блок. */
class TransferProgress(private val label: String = "Передача", private val report: (String) -> Unit = ::println) {
    private var lastPercent = -1

    fun update(done: Long, total: Long) {
        val percent = if (total == 0L) 100 else (done.toDouble() / total * 100).toInt()
        if (percent != lastPercent) {
            report("$label: $percent% ($done / $total байт)")
            lastPercent = percent
        }
    }
}
