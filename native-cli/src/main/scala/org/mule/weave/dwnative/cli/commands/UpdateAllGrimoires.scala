package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.EnvironmentVariableProvider
import org.mule.weave.dwnative.cli.utils.SpellsUtils

class UpdateAllGrimoires(console:Console, envVarProvider: EnvironmentVariableProvider) extends WeaveCommand {

  private val utils = new SpellsUtils(console, envVarProvider)

  def exec(): Int = {
    updateGrimoires()
  }

  def updateGrimoires(): Int = {
    console.info("Updating Grimoires")
    var statusCode = 0
    utils.updateLastUpdateTimeStamp()
    val grimoires = utils.grimoiresFolders().listFiles()
    grimoires.foreach((grimoire) => {
      //If it is not a directory it can be the lastUpdate.txt
      if (grimoire.isDirectory) {
        val updateGrimoireCommand = new UpdateGrimoireCommand(UpdateGrimoireConfig(grimoire),console, envVarProvider)
        val i = updateGrimoireCommand.exec()
        if (i != 0) {
          statusCode = i
        }
      }
    })
    statusCode
  }

}
