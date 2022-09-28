package org.mule.weave.dwnative.utils

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.utils.DataWeaveUtils._

import java.io.File

object DataWeaveUtils {
  val DW_DEFAULT_INPUT_MIMETYPE_VAR: String = "DW_DEFAULT_INPUT_MIMETYPE"
  val DW_DEFAULT_OUTPUT_MIMETYPE_VAR: String = "DW_DEFAULT_OUTPUT_MIMETYPE"
  val DW_HOME_VAR = "DW_HOME"
  val DW_WORKING_DIRECTORY_VAR = "DW_WORKING_PATH"
  val DW_LIB_PATH_VAR = "DW_LIB_PATH"
}

class DataWeaveUtils(console: Console) {

  /**
    * Returns the DW home directory if exists it can be overwritten with env variable DW_HOME
    *
    * @return The home directory
    */
  def getDWHome(): File = {
    val weavehome: Option[String] = console.envVar(DW_HOME_VAR)
    if (weavehome.isDefined) {
      val home = new File(weavehome.get)
      if (!home.exists()) {
        console.error(s" Weave Home Directory `${weavehome}` declared on environment variable `WEAVE_HOME` does not exists.")
      }
      home
    } else {
      console.debug("Env not working trying home directory")
      val defaultDWHomeDir: File = getDefaultDWHome()
      if (defaultDWHomeDir.exists()) {
        defaultDWHomeDir
      } else {
        val dwScriptPath = console.envVar("_")
        if (dwScriptPath.isDefined) {
          val scriptPath = new File(dwScriptPath.get)
          if (scriptPath.isFile && scriptPath.getName == "dw") {
            val homeDirectory = scriptPath.getCanonicalFile.getParentFile.getParentFile
            console.debug(s"Home Directory detected from script at ${homeDirectory.getAbsolutePath}")
            return homeDirectory
          }
        }
        console.warn(s"Unable to detect Weave Home directory so local directory is going to be used. Please either define the env variable `${DW_HOME_VAR}` or copy the weave distro into `${defaultDWHomeDir.getAbsolutePath}`.")
        new File("..")
      }
    }
  }

  def getDefaultDWHome(): File = {
    val homeUser = getUserHome()
    val defaultDWHomeDir = new File(homeUser, ".dw")
    defaultDWHomeDir
  }

  def getUserHome(): File = {
    new File(System.getProperty("user.home"))
  }

  /**
    * Returns the DW home directory if exists it can be overwritten with env variable DW_HOME
    *
    * @return The home directory
    */
  def getWorkingHome(): File = {
    val weavehome = console.envVar(DW_WORKING_DIRECTORY_VAR)
    if (weavehome.isDefined) {
      val home = new File(weavehome.get)
      if (!home.exists()) {
        console.error(s"Weave Working Home Directory `${weavehome}` declared on environment variable `$DW_WORKING_DIRECTORY_VAR` does not exists.")
      }
      home
    } else {
      val tmpDirectory = new File(getDWHome(), "tmp")
      if (!tmpDirectory.exists()) {
        tmpDirectory.mkdirs()
      }
      tmpDirectory
    }
  }

  /**
    * Returns the directory where all default jars are going to be present. It can be overwriten with DW_LIB_PATH
    *
    * @return The file
    */
  def getLibPathHome(): File = {
    val weavehome = console.envVar(DW_LIB_PATH_VAR)
    if (weavehome.isDefined) {
      val home = new File(weavehome.get)
      if (!home.exists()) {
        console.error(s"Weave Library Home Directory `${weavehome}` declared on environment variable `$DW_LIB_PATH_VAR` does not exists.")
      }
      home
    } else {
      new File(getDWHome(), "libs")
    }
  }

  def getCacheHome(): File = {
    new File(getDWHome(), "cache")
  }
}