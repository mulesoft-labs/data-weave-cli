package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.cli.commands.UsageCommand
import org.mule.weave.dwnative.cli.exceptions.CLIException

import java.io.PrintWriter
import java.io.StringWriter


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
        try {
          weaveCommand.exec()
        } catch {
          case exception: CLIException => {
            console.error(exception.getMessage)
            -1
          }
          case exception: Exception => {
            val exceptionString = new StringWriter()
            exception.printStackTrace(new PrintWriter(exceptionString))
            console.error("Unexpected exception happened while executing command. " +
              "Please report this as an issue in https://github.com/mulesoft-labs/data-weave-cli/issues with all the details to reproduce.\n" +
              s"Stacktrace is: ${exceptionString}")
            -1
          }
        }
      }
      case _ => {
        0
      }
    }
  }
}
