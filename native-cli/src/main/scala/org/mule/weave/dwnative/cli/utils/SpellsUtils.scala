package org.mule.weave.dwnative.cli.utils

import org.mule.weave.dwnative.utils.DataWeaveUtils

import java.io.File
import java.nio.file.Files

object SpellsUtils {

  val DATA_WEAVE_GRIMOIRE_FOLDER = "data-weave-grimoire"

  def grimoireName(user: String): String = {
    if (user == null)
      DATA_WEAVE_GRIMOIRE_FOLDER
    else
      s"${user}-$DATA_WEAVE_GRIMOIRE_FOLDER"
  }

  def wizardName(grimoire: String): String = {
    if (grimoire == null)
      "DW"
    else
      grimoire.substring(0, grimoire.length - s"-${DATA_WEAVE_GRIMOIRE_FOLDER}".length)
  }

  def hoursSinceLastUpdate(): Int = {
    val lastModified = lastUpdatedMarkFile().lastModified()
    val millis = System.currentTimeMillis() - lastModified
    (millis / (1000 * 60 * 20)).asInstanceOf[Int]
  }

  def grimoireFolder(wizard: String): File = {
    val grimoiresFolder = grimoiresFolders()
    new File(grimoiresFolder, grimoireName(wizard))
  }

  def grimoiresFolders(): File = {
    val file = new File(DataWeaveUtils.getDWHome(), "grimoires")
    if (!file.exists()) {
      file.mkdirs()
    }
    file
  }




  def buildRepoUrl(user: String): String = {
    val domain = if (user == null) "mulesoft-labs" else user
    val repo = grimoireName(user)
    val url = s"https://github.com/${domain}/${repo}.git"
    url
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
