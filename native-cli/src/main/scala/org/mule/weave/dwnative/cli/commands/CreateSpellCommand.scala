package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console

import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets

class CreateSpellCommand(val spellName: String, console: Console) extends WeaveCommand {

  def exec(): Int = {
    var statusCode = 0
    val homeFolder = new File(spellName)
    if (homeFolder.exists()) {
      console.error(s"Spell `${spellName}` already exists.")
      statusCode = -1
    } else {
      if (homeFolder.mkdirs()) {
        val srcFolder = new File(homeFolder, "src")
        srcFolder.mkdirs()
        val mainFile = new File(srcFolder, "Main.dwl")
        val writer = new FileWriter(mainFile, StandardCharsets.UTF_8)
        writer.write("%dw 2.0\n---")
        console.info(s"Write your magic spell in `${mainFile.getAbsolutePath}`.")
        writer.close()
      } else {
        console.error(s"Unable to create folder `${spellName}`.")
        statusCode = -1
      }
    }
    statusCode
  }


}
