package org.mule.weave.dwnative.utils

import java.io.File

object DataWeaveUtils {
  /**
    * Returns the DW home directory if exists it can be overwritten with env variable DW_HOME
    *
    * @return The home directory
    */
  def getDWHome(): File = {
    val homeUser = new File(System.getProperty("user.home"))
    val weavehome = System.getenv("DW_HOME")
    if (weavehome != null) {
      val home = new File(weavehome)
      if (!home.exists()) {
        println(AnsiColor.red(s"[error] Weave Home Directory `${weavehome}` declared on environment variable `WEAVE_HOME` does not exists."))
      }
      home
    } else {
      if (WeaveProperties.verbose) {
        println("[debug] Env not working trying home directory")
      }
      val defaultDWHomeDir = new File(homeUser, ".dw")
      if (defaultDWHomeDir.exists()) {
        defaultDWHomeDir
      } else {
        val dwScriptPath = System.getenv("_")
        if (dwScriptPath != null) {
          val scriptPath = new File(dwScriptPath)
          if (scriptPath.isFile && scriptPath.getName == "dw") {
            val homeDirectory = scriptPath.getCanonicalFile.getParentFile.getParentFile
            if (WeaveProperties.verbose) {
              println(s"[debug] Home Directory detected from script at ${homeDirectory.getAbsolutePath}")
            }
            return homeDirectory
          }
        }
        println(AnsiColor.yellow(s"[warning] Unable to detect Weave Home directory so local directory is going to be used. Please either define the env variable WEAVE_HOME or copy the weave distro into `${defaultDWHomeDir.getAbsolutePath}`."))
        new File("..")
      }
    }
  }

  /**
    * Returns the DW home directory if exists it can be overwritten with env variable DW_HOME
    *
    * @return The home directory
    */
  def getWorkingHome(): File = {
    val weavehome = System.getenv("DW_WORKING_PATH")
    if (weavehome != null) {
      val home = new File(weavehome)
      if (!home.exists()) {
        println(AnsiColor.red(s"[error] Weave Working Home Directory `${weavehome}` declared on environment variable `DW_WORKING_PATH` does not exists."))
      }
      home
    } else {
      new File(getDWHome(), "tmp")
    }
  }

  /**
    * Returns the directory where all default jars are going to be present. It can be overwriten with DW_LIB_PATH
    *
    * @return The file
    */
  def getLibPathHome(): File = {
    val weavehome = System.getenv("DW_LIB_PATH")
    if (weavehome != null) {
      val home = new File(weavehome)
      if (!home.exists()) {
        println(AnsiColor.red(s"[error] Weave Library Home Directory `${weavehome}` declared on environment variable `DW_LIB_PATH` does not exists."))
      }
      home
    } else {
      new File(getDWHome(), "libs")
    }
  }

  def sanitizeFilename(inputName: String): String = inputName.replaceAll("[^a-zA-Z0-9-_\\.]", "_")
}

object WeaveProperties {
  var verbose: Boolean = false
}
