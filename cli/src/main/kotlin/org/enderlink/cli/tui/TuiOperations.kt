package org.enderlink.cli.tui

import org.enderlink.cli.CliOptions
import org.enderlink.cli.parseArguments
import org.enderlink.cli.command.OperationControl
import org.enderlink.cli.command.WorldCommand
import java.nio.file.Path

/** TUI defaults feed the same validation and operations as one-shot CLI commands. */
internal class TuiOperations(
    private val run: (CliOptions, (String) -> Unit, OperationControl) -> Unit = { options, report, control ->
        WorldCommand(report, report, control).run(options)
    },
) {
    fun options(command: String, arguments: List<String>, repository: Path?, profile: Path): CliOptions {
        val args = arguments.toMutableList()
        if (command == "world-publish" || command == "world-send" && "--source" in args) {
            require(args.remove("--closed")) { "Закройте мир Minecraft или остановите сервер и добавьте --closed." }
        }
        if ("--repository" !in args) {
            requireNotNull(repository) { "Выберите repository <путь> или укажите --repository <путь>." }
            args += listOf("--repository", repository.toString())
        }
        if (("--iroh" in args || "--peer" in args) && "--profile" !in args) args += listOf("--profile", profile.toString())
        return parseArguments((listOf(command) + args).toTypedArray())
    }

    fun execute(options: CliOptions, report: (String) -> Unit, control: OperationControl) = run(options, report, control)
}
