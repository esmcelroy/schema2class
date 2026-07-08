package ca.esmcelroy.schema2class.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class Schema2ClassCli : CliktCommand(
    name = "schema2class",
    help = "Generate idiomatic Kotlin classes from XSD and JSON Schema documents",
) {
    override fun run() = Unit
}

fun main(args: Array<String>) = Schema2ClassCli()
    .subcommands(GenerateCommand())
    .main(args)
