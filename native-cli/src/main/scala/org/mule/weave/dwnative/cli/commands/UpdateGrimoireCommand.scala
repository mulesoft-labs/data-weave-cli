package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils.wizardName

import java.io.File

class UpdateGrimoireCommand(config: UpdateGrimoireConfig, logger: Console) extends WeaveCommand {

  def exec(): Int = {
    updateGrimoire(config.grimoire)
  }

  def updateGrimoire(grimoire: File): Int = {
    logger.info(s"Updating `${wizardName(grimoire.getName)}'s` Grimoire.")
    val processBuilder = new ProcessBuilder("git", "pull")
    processBuilder.directory(grimoire)
    processBuilder.inheritIO()
    processBuilder.start().waitFor()
  }

}

case class UpdateGrimoireConfig(grimoire: File)
