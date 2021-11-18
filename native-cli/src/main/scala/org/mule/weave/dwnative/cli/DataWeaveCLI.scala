package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.cli.commands.UsageCommand


object DataWeaveCLI extends App {
  {
    val i = new DataWeaveCLIRunner().run(args, DefaultConsole)
    System.exit(i)
  }
}

class DataWeaveCLIRunner {

  def run(args: Array[String], console: Console = DefaultConsole): Int = {
    val parser = new CLIArgumentsParser(console)
    val scriptToRun = parser.parse(args)
    scriptToRun match {
      case Right(message) if (message.nonEmpty) => {
        console.error("Parameters configuration error:")
        console.error(message)
        new UsageCommand(console).exec()
        -1
      }
      case Left(weaveCommand) => {
        weaveCommand.exec()
      }
      case _ => {
        0
      }
    }
  }
}
