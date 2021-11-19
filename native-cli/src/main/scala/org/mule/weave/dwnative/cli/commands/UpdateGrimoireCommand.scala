package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils

import java.io.File

class UpdateGrimoireCommand(config: UpdateGrimoireConfig, console: Console) extends WeaveCommand {

  private val utils = new SpellsUtils(console)

  def exec(): Int = {
    updateGrimoire(config.grimoire)
  }

  def updateGrimoire(grimoire: File): Int = {
    console.info(s"Updating `${utils.wizardName(grimoire.getName)}'s` Grimoire.")
    val processBuilder = new ProcessBuilder("git", "pull")
    processBuilder.directory(grimoire)
    processBuilder.inheritIO()
    processBuilder.start().waitFor()
  }

}

case class UpdateGrimoireConfig(grimoire: File)
