package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils
import org.mule.weave.dwnative.cli.utils.SpellsUtils._

import java.io.File
import scala.io.Source

class ListSpellsCommand(console: Console) extends WeaveCommand {

  private val utils = new SpellsUtils(console)

  def exec(): Int = {
    val spells: String = listSpells()
    console.info(spells)
    0
  }

  private def listSpells(): String = {
    val builder = new StringBuilder()
    builder.append("Spells:\n")
    val grimoires: File = utils.grimoiresFolders()
    val grimoiresDirs: Array[File] = grimoires.listFiles()
    if (grimoiresDirs != null) {
      grimoiresDirs.foreach((g) => {
        val name = if (g.getName.equals(DATA_WEAVE_GRIMOIRE_FOLDER)) "" else utils.wizardName(g.getName) + "/"
        val spells = g.listFiles()
        if (spells != null) {
          spells.foreach((s) => {
            if (s.isDirectory && !s.isHidden) {
              builder.append(s" - ${name}${s.getName}:")
              val readme = new File(s, "Readme.md")
              if (readme.exists()) {
                val source = Source.fromFile(readme, "UTF-8")
                builder.append("\n     ")
                builder.append(source.mkString.replaceAllLiterally("\n", "\n     ").slice(0, 450))
                builder.append("\n")
                source.close()
              }
              builder.append("\n")
            }
          })
        }
      })
    }
    builder.toString()
  }

}
