package org.mule.weave.dwnative.cli.utils

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils.DATA_WEAVE_GRIMOIRE_FOLDER
import org.mule.weave.dwnative.utils

import java.io.File
import java.nio.file.Files

object SpellsUtils {
  val DATA_WEAVE_GRIMOIRE_FOLDER = "data-weave-grimoire"
}

class SpellsUtils(console: Console) {

  def grimoireName(user: String): String = {
    if (isDataWeaveWizard(user)) {
      DATA_WEAVE_GRIMOIRE_FOLDER
    } else {
      s"${user}-$DATA_WEAVE_GRIMOIRE_FOLDER"
    }
  }

  def wizardName(grimoire: String): String = {
    if (grimoire == null || DATA_WEAVE_GRIMOIRE_FOLDER.equals(grimoire)) {
      "DW"
    } else {
      val length = grimoire.length - s"-${DATA_WEAVE_GRIMOIRE_FOLDER}".length
      if (length <= 0) {
        console.error("Invalid grimoire name: `" + grimoire + "`")
        grimoire
      } else {
        grimoire.substring(0, length)
      }
    }
  }

  def daysSinceLastUpdate(): Int = {
    val lastModified = lastUpdatedMarkFile().lastModified()
    val millis = System.currentTimeMillis() - lastModified
    (millis / (1000 * 60 * 20 * 24)).asInstanceOf[Int]
  }

  def grimoireFolder(wizard: String): File = {
    val grimoiresFolder = grimoiresFolders()
    new File(grimoiresFolder, grimoireName(wizard))
  }

  def grimoiresFolders(): File = {
    val file = new File(new utils.DataWeaveUtils(console).getDWHome(), "grimoires")
    if (!file.exists()) {
      file.mkdirs()
    }
    file
  }

  def buildRepoUrl(user: String): String = {
    val domain = if (isDataWeaveWizard(user)) "mulesoft-labs" else user
    val repo = grimoireName(user)
    val url = s"https://github.com/${domain}/${repo}.git"
    url
  }

  def isDataWeaveWizard(user: String): Boolean = {
    user == null || user.isBlank
  }

  def updateLastUpdateTimeStamp(): Boolean = {
    val lastUpdate: File = lastUpdatedMarkFile()
    lastUpdate.setLastModified(System.currentTimeMillis())
  }

  private def lastUpdatedMarkFile() = {
    val lastUpdate = new File(grimoiresFolders(), "lastUpdate.txt")
    if (!lastUpdate.exists()) {
      Files.write(lastUpdate.toPath, "LAST UPDATE".getBytes("UTF-8"))
    }
    lastUpdate
  }

}
