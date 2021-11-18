package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils._

class UpdateAllGrimoires(logger:Console) extends WeaveCommand {

  def exec(): Int = {
    updateGrimoires()
  }

  def updateGrimoires(): Int = {
    logger.info("Updating Grimoires")
    var statusCode = 0
    updateLastUpdateTimeStamp()
    val grimoires = grimoiresFolders().listFiles()
    grimoires.foreach((grimoire) => {
      //If it is not a directory it can be the lastUpdate.txt
      if (grimoire.isDirectory) {
        val updateGrimoireCommand = new UpdateGrimoireCommand(UpdateGrimoireConfig(grimoire),logger)
        val i = updateGrimoireCommand.exec()
        if (i != 0) {
          statusCode = i
        }
      }
    })
    statusCode
  }

}
