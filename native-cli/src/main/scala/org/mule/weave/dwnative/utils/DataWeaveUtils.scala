package org.mule.weave.dwnative.utils

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.EnvironmentVariableProvider

import java.io.File

class DataWeaveUtils(console: Console, envVarProvider: EnvironmentVariableProvider) {

  /**
    * Returns the DW home directory if exists it can be overwritten with env variable DW_HOME
    *
    * @return The home directory
    */
  def getDWHome(): File = {
    val maybeHomeVariable = envVarProvider.envVar(EnvironmentVariableProvider.DW_HOME_VAR)
    if (maybeHomeVariable.isDefined) {
      val homeVariable = maybeHomeVariable.get
      val home = new File(homeVariable)
      if (!home.exists()) {
        console.error(s" Weave Home Directory `$homeVariable` declared on environment variable `WEAVE_HOME` does not exists.")
      }
      home
    } else {
      console.debug("Env not working trying home directory")
      val defaultDWHomeDir: File = getDefaultDWHome()
      if (defaultDWHomeDir.exists()) {
        defaultDWHomeDir
      } else {
        val dwScriptPath = envVarProvider.envVar("_")
        if (dwScriptPath.isDefined) {
          val scriptPath = new File(dwScriptPath.get)
          if (scriptPath.isFile && scriptPath.getName == "dw") {
            val homeDirectory = scriptPath.getCanonicalFile.getParentFile.getParentFile
            console.debug(s"Home Directory detected from script at ${homeDirectory.getAbsolutePath}")
            return homeDirectory
          }
        }
        console.warn(s"Unable to detect Weave Home directory so local directory is going to be used. Please either define the env variable `${EnvironmentVariableProvider.DW_HOME_VAR}` or copy the weave distro into `${defaultDWHomeDir.getAbsolutePath}`.")
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
    val maybeWorkingDirectoryVariable = envVarProvider.envVar(EnvironmentVariableProvider.DW_WORKING_DIRECTORY_VAR)
    if (maybeWorkingDirectoryVariable.isDefined) {
      val workingDirectoryVariable = maybeWorkingDirectoryVariable.get
      val workingDirectory = new File(workingDirectoryVariable)
      if (!workingDirectory.exists()) {
        console.error(s"Weave Working Home Directory `$workingDirectoryVariable` declared on environment variable `${EnvironmentVariableProvider.DW_WORKING_DIRECTORY_VAR}` does not exists.")
      }
      workingDirectory
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
    val maybeWeaveLibPathVariable = envVarProvider.envVar(EnvironmentVariableProvider.DW_LIB_PATH_VAR)
    if (maybeWeaveLibPathVariable.isDefined) {
      val weaveLibPathVariable = maybeWeaveLibPathVariable.get
      val weaveLibPathVarDirectory = new File(weaveLibPathVariable)
      if (!weaveLibPathVarDirectory.exists()) {
        console.error(s"Weave Library Home Directory `$weaveLibPathVariable` declared on environment variable `${EnvironmentVariableProvider.DW_LIB_PATH_VAR}` does not exists.")
      }
      weaveLibPathVarDirectory
    } else {
      new File(getDWHome(), "libs")
    }
  }

  def sanitizeFilename(inputName: String): String = inputName.replaceAll("[^a-zA-Z0-9-_.]", "_")
}