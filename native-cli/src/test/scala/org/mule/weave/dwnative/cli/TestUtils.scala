package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.utils.FileUtils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object TestUtils {

  def getMyLocalSpell: File = {
    val file = getSpellsFolder
    val localSpell = new File(file, "MyLocalSpell")
    localSpell
  }

  def getMyLocalSpellWithLib: File = {
    val file = getSpellsFolder
    val localSpell = new File(file, "MyLocalSpellWithLib")
    localSpell
  }

  def getSpellsFolder: File = {
    val spellsUrls = getClass.getClassLoader.getResource("spells/spells.txt")
    val file = new File(spellsUrls.getFile)
    val workingDirectory: Path = Files.createTempDirectory("spell")
    FileUtils.copyFolder(file.getParentFile.toPath, workingDirectory)
    workingDirectory.toFile
  }

  def getScriptFolder(script: String): File = {
    val parent = getScriptsFolder
    new File(parent, script)
  }
  
  def getScriptsFolder: File = {
    val scriptsUrls = getClass.getClassLoader.getResource("scripts/scripts.txt")
    val file = new File(scriptsUrls.getFile)
    val workingDirectory: Path = Files.createTempDirectory("scripts")
    FileUtils.copyFolder(file.getParentFile.toPath, workingDirectory)
    workingDirectory.toFile
  }

}
