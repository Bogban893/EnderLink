package org.enderlink.cli.command

import org.enderlink.cli.CliOptions
import org.enderlink.network.iroh.IrohProfile
import java.nio.file.Path

class ProfileCommand(private val report: (String) -> Unit = ::println) {
    fun run(options: CliOptions): Pair<String, Map<String, String>> =
        IrohProfile.open(options.profile?.let(Path::of) ?: IrohProfile.defaultDirectory()).use { profile ->
            when (options.command) {
                "add" -> { profile.add(requireNotNull(options.friendName), requireNotNull(options.friendId)); report("Друг сохранён.") }
                "remove" -> { profile.remove(requireNotNull(options.friendName)); report("Друг удалён.") }
                "friends" -> {
                    if (profile.friends().isEmpty()) report("Друзей пока нет. Используйте add <имя> <ID>.")
                    profile.friends().forEach { (name, id) -> report("$name $id") }
                }
                else -> report("Ваш ID: ${profile.id}")
            }
            profile.id to profile.friends()
        }
}
