package org.mule.weave.dwnative.cli.commands

/**
  * The trait that represents any command that can be executed from the cli
  */
trait WeaveCommand {

  /**
    * Executes the command
    *
    * @return The exit status code of the command
    */
  def exec(): Int
}
