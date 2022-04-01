package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.cli.commands.AddWizardCommand
import org.mule.weave.dwnative.cli.commands.CloneWizardConfig
import org.mule.weave.dwnative.cli.commands.CreateSpellCommand
import org.mule.weave.dwnative.cli.commands.ListSpellsCommand
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand
import org.mule.weave.dwnative.cli.commands.UpdateAllGrimoires
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireCommand
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireConfig
import org.mule.weave.dwnative.cli.commands.UsageCommand
import org.mule.weave.dwnative.cli.commands.VersionCommand
import org.mule.weave.dwnative.cli.commands.WeaveCommand
import org.mule.weave.dwnative.cli.commands.WeaveModule
import org.mule.weave.dwnative.cli.commands.WeaveRunnerConfig
import org.mule.weave.dwnative.cli.exceptions.ResourceNotFoundException
import org.mule.weave.dwnative.cli.utils.SpellsUtils
import org.mule.weave.dwnative.utils.FileUtils
import org.mule.weave.v2.io.FileHelper
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.runtime.utils.AnsiColor.red
import org.mule.weave.v2.sdk.NameIdentifierHelper

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

class CLIArgumentsParser(console: Console) {

  private val utils = new SpellsUtils(console)

  def parse(args: Array[String]): Either[WeaveCommand, String] = {
    var i = 0
    //Use the current directory as the path
    var path: String = ""
    var scriptToRun: Option[(NativeRuntime) => WeaveModule] = None
    var output: Option[String] = None
    var profile: Boolean = false
    var eval: Boolean = false
    var watch: Boolean = false
    val filesToWatch: ArrayBuffer[File] = ArrayBuffer()
    var cleanCache: Boolean = false

    val inputs: mutable.Map[String, File] = mutable.Map()
    val properties: mutable.Map[String, String] = mutable.Map()

    while (i < args.length) {
      args(i) match {
        case "--path" => {
          if (i + 1 < args.length) {
            i = i + 1
            if (path.isEmpty) {
              path = args(i)
            } else {
              path = path + File.pathSeparator + args(i)
            }
          } else {
            return Right("Missing path expression")
          }
        }
        case "-p" | "--property" => {
          if (i + 2 < args.length) {
            val propName: String = args(i + 1)
            val propValue: String = args(i + 2)
            properties.put(propName, propValue)
          } else {
            return Right(red("Invalid amount of arguments on `Property`."))
          }
          i = i + 2
        }
        case "-v" | "--verbose" => {
          console.enableDebug()
        }
        case "-h" | "--help" => {
          return Left(new UsageCommand(console))
        }
        case "--version" => {
          return Left(new VersionCommand(console))
        }
        case "--update-grimoires" => {
          return Left(new UpdateAllGrimoires(console))
        }
        case "--add-wizard" => {
          if (i + 1 < args.length) {
            i = i + 1
            val wizardName = args(i)
            return Left(new AddWizardCommand(CloneWizardConfig(wizardName), console))
          } else {
            return Right("Missing <wizard-name>")
          }
        }
        case "--clean-cache" => {
          cleanCache = true
        }
        case "--watch" => {
          watch = true
        }
        case "--new-spell" => {
          if (i + 1 < args.length) {
            i = i + 1
            val spellName = args(i)
            return Left(new CreateSpellCommand(spellName, console))
          } else {
            return Right("Missing <spell-name>")
          }
        }
        case "--list-spells" => {
          return Left(new ListSpellsCommand(console))
        }
        case "-s" | "--spell" => {
          if (i + 1 < args.length) {
            i = i + 1
            val spell = args(i)
            val wizard = if (spell.contains("/")) {
              spell.split("/").head
            } else {
              null
            }
            var spellName = if (spell.contains("/")) {
              spell.split("/")(1)
            } else {
              spell
            }

            var fileName = "Main.dwl"
            var nameIdentifier = NameIdentifier("Main")

            if (spellName.contains("@")) {
              val spellParts = spellName.split("@")
              spellName = spellParts.head
              nameIdentifier = NameIdentifier(spellParts.last)
              fileName = NameIdentifierHelper.toWeaveFilePath(nameIdentifier, File.pathSeparator)
            }

            val lastUpdate = utils.hoursSinceLastUpdate()
            //Update grimoires every day
            if (lastUpdate > 24) {
              new UpdateAllGrimoires(console).exec()
            }

            var wizardGrimoire: File = utils.grimoireFolder(wizard)
            if (!wizardGrimoire.exists()) {
              new AddWizardCommand(CloneWizardConfig(wizard), console).exec()
            }
            wizardGrimoire = utils.grimoireFolder(wizard)
            val wizardName = if (wizard == null) "Weave" else wizard
            if (!wizardGrimoire.exists()) {
              return Right(s"Unable to get Wise `$wizardName's` Grimoire.")
            }

            val spellFolder = new File(wizardGrimoire, spellName)
            if (!spellFolder.exists()) {
              new UpdateGrimoireCommand(UpdateGrimoireConfig(wizardGrimoire), console).exec()
            }

            if (!spellFolder.exists()) {
              return Right(s"Unable find ${spellName} in Wise `${wizardName}'s` Grimoire.")
            }

            val srcFolder = new File(spellFolder, "src")
            val mainFile = new File(srcFolder, fileName)
            if (!mainFile.isFile) {
              return Right(s"Unable find `${fileName}` in the spell: `${spellName}` inside Wise `${wizardName}'s` Grimoire.")
            }
            if (path.isEmpty) {
              path = srcFolder.getAbsolutePath
            } else {
              path = path + File.pathSeparator + srcFolder.getAbsolutePath
            }
            scriptToRun = Some((_) => {
              WeaveModule(fileToString(mainFile), nameIdentifier.toString())
            })
          } else {
            return Right("Missing <spellName>")
          }
        }
        case "--local-spell" => {
          if (i + 1 < args.length) {
            i = i + 1
            console.info("Running local spell")
            val spell: String = args(i)

            var fileName = "Main.dwl"
            var nameIdentifier = NameIdentifier("Main")
            var spellName = spell
            if (spell.contains("@")) {
              val spellParts = spell.split("@")
              spellName = spellParts.head
              nameIdentifier = NameIdentifier(spellParts.last)
              fileName = NameIdentifierHelper.toWeaveFilePath(nameIdentifier, File.separator)
            }

            val spellFolder: File = new File(spellName)
            if (!spellFolder.exists()) {
              return Right(s"Unable find `${spellName}` folder.")
            }
            val srcFolder = new File(spellFolder, "src")

            val mainFile = new File(srcFolder, fileName)
            if (!mainFile.isFile) {
              return Right(s"Unable find `${fileName}` in the spell: `${spell}`.")
            }
            //Watch all files in the src folder
            filesToWatch ++= FileUtils.tree(srcFolder)
            if (path.isEmpty) {
              path = srcFolder.getAbsolutePath
            } else {
              path = path + File.pathSeparator + srcFolder.getAbsolutePath
            }
            scriptToRun = Some((_) => {
              WeaveModule(fileToString(mainFile), nameIdentifier.toString())
            })
          } else {
            return Right("Missing <spell-folder>")
          }
        }
        case "-i" | "--input" => {
          if (i + 2 < args.length) {
            val input: File = new File(args(i + 2))
            val inputName: String = args(i + 1)
            if (input.exists()) {
              filesToWatch.+=(input)
              inputs.put(inputName, input)
            } else {
              return Right(red(s"Invalid input file $inputName ${input.getAbsolutePath}."))
            }
          } else {
            return Right(red("Invalid amount of arguments on input."))
          }
          i = i + 2
        }
        case "-o" | "--output" => {
          if (i + 1 < args.length) {
            i = i + 1
            output = Some(args(i))
          } else {
            return Right("Missing <outputPath>")
          }
        }
        case "--main" | "-m" => {
          if (i + 1 < args.length) {
            i = i + 1
            val mainScriptName: String = args(i)
            scriptToRun = Some((nativeRuntime) => {
              val maybeString = nativeRuntime.getResourceContent(NameIdentifier(mainScriptName))
              if (maybeString.isDefined) {
                WeaveModule(maybeString.get, mainScriptName)
              } else {
                throw new ResourceNotFoundException(mainScriptName)
              }
            })
          } else {
            return Right("Missing main name identifier")
          }
        }
        case "-f" | "--file" => {
          if (i + 1 < args.length) {
            i = i + 1
            val scriptFile = new File(args(i))
            if (scriptFile.exists()) {
              filesToWatch.+=(scriptFile)
              scriptToRun = Some((_) => WeaveModule(fileToString(scriptFile), FileHelper.baseName(scriptFile)))
            } else {
              return Right(s"File `${args(i)}` was not found.")
            }
          } else {
            return Right("Missing script file path.")
          }
        }
        case "--profile" => {
          profile = true
        }
        case "--eval" => {
          eval = true
        }
        case script if (i + 1 == args.length) => {
          scriptToRun = Some((_) => WeaveModule(script, NameIdentifier.ANONYMOUS_NAME.toString()))
        }
        case arg => {
          return Right(s"Invalid argument ${arg}")
        }
      }
      i = i + 1
    }

    val paths = if (path.isEmpty) Array[String]() else path.split(File.pathSeparatorChar)
    if (scriptToRun.isEmpty) {
      Right(s"Missing <scriptContent> or -m <nameIdentifier> of -f <filePath> or --spell ")
    } else {
      val config: WeaveRunnerConfig = WeaveRunnerConfig(paths, profile, eval, cleanCache, scriptToRun.get, properties.toMap, inputs.toMap, output, filesToWatch, watch)
      Left(new RunWeaveCommand(config, console))
    }
  }


  def fileToString(scriptFile: File): String = {
    val source = Source.fromFile(scriptFile, "UTF-8")
    try {
      source.mkString
    } finally {
      source.close()
    }
  }


}
