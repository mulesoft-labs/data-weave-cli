package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.utils.FileUtils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object TestUtils {

  def getMyLocalSpell = {
    val file = getSpellsFolder()
    val localSpell = new File(file, "MyLocalSpell")
    localSpell
  }

  def getMyLocalSpellWithLib = {
    val file = getSpellsFolder()
    val localSpell = new File(file, "MyLocalSpellWithLib")
    localSpell
  }

  def getSimpleSpellWithDependencies = {
    val file = getSpellsFolder()
    val localSpell = new File(file, "SimpleSpellWithDependencies")
    localSpell
  }


  def getSpellsFolder(): File = {
    val spellsUrls = getClass.getClassLoader.getResource("spells/spells.txt")
    val file = new File(spellsUrls.getFile)
    val workingDirectory: Path = Files.createTempDirectory("spell")
    FileUtils.copyFolder(file.getParentFile.toPath, workingDirectory)
    workingDirectory.toFile
  }

}
