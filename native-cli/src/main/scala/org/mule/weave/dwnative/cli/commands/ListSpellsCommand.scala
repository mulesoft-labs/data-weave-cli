package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.utils.SpellsUtils
import org.mule.weave.dwnative.cli.utils.SpellsUtils._

import java.io.File
import java.io.FileFilter
import scala.collection.mutable
import scala.io.Source

class ListSpellsCommand(console: Console) extends WeaveCommand {

  private val utils = new SpellsUtils(console)

  def exec(): Int = {
    val spells: String = listSpells()
    console.info(spells)
    ExitCodes.SUCCESS
  }

  private def listSpells(): String = {
    val builder = new mutable.StringBuilder()
    builder.append("Spells:\n")
    val grimoires: File = utils.grimoiresFolders()
    var grimoiresDirs: Array[File] = listGrimoires(grimoires)
    if (grimoiresDirs == null || !new File(grimoires, DATA_WEAVE_GRIMOIRE_FOLDER).exists()) {
      new AddWizardCommand(CloneWizardConfig(null), console).exec()
      grimoiresDirs = listGrimoires(grimoires)
    }
    if (grimoiresDirs != null) {
      grimoiresDirs
        .foreach((file) => {
          val name = if (file.getName.equals(DATA_WEAVE_GRIMOIRE_FOLDER)) "" else utils.wizardName(file.getName) + "/"
          val spells = file.listFiles()
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

  private def listGrimoires(grimoires: File) = {
    grimoires.listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = pathname.isDirectory && !pathname.isHidden
    })
  }
}
