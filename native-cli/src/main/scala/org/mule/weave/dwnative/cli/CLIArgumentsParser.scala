package org.mule.weave.dwnative.cli

import org.apache.commons.cli.DefaultParser
import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.cli.commands.AddWizardCommand
import org.mule.weave.dwnative.cli.commands.CloneWizardConfig
import org.mule.weave.dwnative.cli.commands.CreateSpellCommand
import org.mule.weave.dwnative.cli.commands.HelpCommand
import org.mule.weave.dwnative.cli.commands.ListSpellsCommand
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand
import org.mule.weave.dwnative.cli.commands.UpdateAllGrimoires
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireCommand
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireConfig
import org.mule.weave.dwnative.cli.commands.VersionCommand
import org.mule.weave.dwnative.cli.commands.WeaveCommand
import org.mule.weave.dwnative.cli.commands.WeaveModule
import org.mule.weave.dwnative.cli.commands.WeaveRunnerConfig
import org.mule.weave.dwnative.cli.utils.SpellsUtils
import org.mule.weave.dwnative.dependencies.DependencyResolutionResult
import org.mule.weave.dwnative.dependencies.SpellDependencyManager
import org.mule.weave.v2.io.FileHelper
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.runtime.utils.AnsiColor.red
import org.mule.weave.v2.sdk.NameIdentifierHelper
import org.mule.weave.v2.utils.DataWeaveVersion

import java.io.File
import scala.collection.mutable
import scala.io.Source

class CLIArgumentsParser(console: Console) {
  private val utils = new SpellsUtils(console)
  
  def parse(args: Array[String]): Either[WeaveCommand, String] = {
    val parser = new DefaultParser
    
    //Use the current directory as the path
    var path: String = ""
    var scriptToRun: Option[NativeRuntime => WeaveModule] = None
    var output: Option[String] = None
    var eval: Boolean = false
    var maybePrivileges: Option[Seq[String]] = None
    var maybeLanguageLevel: Option[DataWeaveVersion] = None

    var dependencyResolver: Option[(NativeRuntime) => Array[DependencyResolutionResult]] = None

    val inputs: mutable.Map[String, File] = mutable.Map()
    val params: mutable.Map[String, String] = mutable.Map()
    
    try {
      val commandLine = parser.parse(Options.OPTIONS, args)
      
      if (commandLine.hasOption(Options.PARAMETER)) {
        val paramsArgs = commandLine.getOptionValues(Options.PARAMETER)
        if (paramsArgs != null && (paramsArgs.length % 2) == 0) {
          val middle = paramsArgs.length / 2
          for ( i <- 0 until middle) {
            val index = i * 2
            val name: String = paramsArgs(index)
            val value: String = paramsArgs(index + 1)
            params.put(name, value)
          }
        } else {
          return Right(red("Invalid amount of arguments on `parameter`."))
        }
      }
      
      if (commandLine.hasOption(Options.VERBOSE)) {
        console.enableDebug()
      }

      if (commandLine.hasOption(Options.SILENT)) {
        console.enableSilent()
      }
      
      if (commandLine.hasOption(Options.HELP)) {
        return Left(HelpCommand(console))
      }
      
      if (commandLine.hasOption(Options.VERSION)) {
        return Left(new VersionCommand(console))
      }
      
      if (commandLine.hasOption(Options.UPDATE_GRIMOIRES)) {
        return Left(new UpdateAllGrimoires(console))
      }
      
      if (commandLine.hasOption(Options.ADD_WIZARD)) {
        val wizardName = commandLine.getOptionValue(Options.ADD_WIZARD)
        if (wizardName != null && !wizardName.isBlank) {
          return Left(new AddWizardCommand(CloneWizardConfig(wizardName), console))
        } else {
          return Right("Missing <wizard-name>")
        }
      }

      if (commandLine.hasOption(Options.NEW_SPELL)) {
        val spellName = commandLine.getOptionValue(Options.NEW_SPELL)
        if (spellName != null && !spellName.isBlank) {
          return Left(new CreateSpellCommand(spellName, console))
        } else {
          return Right("Missing <spell-name>")
        }
      }
      
      if (commandLine.hasOption(Options.LIST_SPELLS)) {
        return Left(new ListSpellsCommand(console))
      }
      
      if (commandLine.hasOption(Options.SPELL)) {
        val spell = commandLine.getOptionValue(Options.SPELL)
        if (spell != null && !spell.isBlank) {
          val wizard = if (spell.contains("/")) {
            spell.split("/").head
          } else {
            null
          }
          var spellName: String = if (spell.contains("/")) {
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

          val lastUpdate = utils.daysSinceLastUpdate()
          if (lastUpdate > 30) {
            console.info(s"Your spells are getting old. ${lastUpdate} days since last update. Please run \n dw --update-grimoires")
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

          val spellFolder: File = new File(wizardGrimoire, spellName)
          if (!spellFolder.exists()) {
            new UpdateGrimoireCommand(UpdateGrimoireConfig(wizardGrimoire), console).exec()
          }

          val manager = new SpellDependencyManager(spellFolder, console)
          dependencyResolver = Some(manager.resolveDependencies)

          if (!spellFolder.exists()) {
            return Right(s"Unable find $spellName in Wise `$wizardName's` Grimoire.")
          }

          val srcFolder = new File(spellFolder, "src")
          val mainFile = new File(srcFolder, fileName)
          if (!mainFile.isFile) {
            return Right(s"Unable find `$fileName` in the spell: `$spellName` inside Wise `$wizardName's` Grimoire.")
          }
          if (path.isEmpty) {
            path = srcFolder.getAbsolutePath
          } else {
            path = path + File.pathSeparator + srcFolder.getAbsolutePath
          }
          scriptToRun = Some(_ => {
            WeaveModule(fileToString(mainFile), nameIdentifier.toString())
          })
        } else {
          return Right("Missing <spell-name>")
        }
      }
      
      if (commandLine.hasOption(Options.LOCAL_SPELL)) {
        val spell = commandLine.getOptionValue(Options.LOCAL_SPELL)
        if (spell != null && !spell.isBlank) {
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
            return Right(s"Unable find `$spellName` folder.")
          }

          val manager = new SpellDependencyManager(spellFolder, console)
          dependencyResolver = Some(manager.resolveDependencies)

          val srcFolder = new File(spellFolder, "src")

          val mainFile = new File(srcFolder, fileName)
          if (!mainFile.isFile) {
            return Right(s"Unable find `$fileName` in the spell: `$spell`.")
          }
          if (path.isEmpty) {
            path = srcFolder.getAbsolutePath
          } else {
            path = path + File.pathSeparator + srcFolder.getAbsolutePath
          }
          scriptToRun = Some(_ => {
            WeaveModule(fileToString(mainFile), nameIdentifier.toString())
          })
        } else {
          return Right("Missing <spell-folder>")
        }
      }

      if (commandLine.hasOption(Options.INPUT)) {
        val inputArgs = commandLine.getOptionValues(Options.INPUT)
        if (inputArgs != null && (inputArgs.length % 2) == 0) {
          val middle = inputArgs.length / 2
          for ( i <- 0 until middle) {
            val index = i * 2
            val input: File = new File(inputArgs(index + 1))
            val inputName: String = inputArgs(index)
            if (input.exists()) {
              inputs.put(inputName, input)
            } else {
              return Right(red(s"Invalid input file $inputName ${input.getAbsolutePath}."))
            }
          }
        } else {
          return Right(red("Invalid amount of arguments on input."))
        }
      }
      
      if (commandLine.hasOption(Options.OUTPUT)) {
        val outputValue = commandLine.getOptionValue(Options.OUTPUT)
        if (outputValue != null && !outputValue.isBlank) {
          output = Some(outputValue)
        } else {
          return Right("Missing <output-path>")
        }
      }
      
      if (commandLine.hasOption(Options.FILE)) {
        val filePath = commandLine.getOptionValue(Options.FILE)
        if (filePath != null && !filePath.isBlank) {
          val scriptFile = new File(filePath)
          if (scriptFile.exists()) {
            scriptToRun = Some(_ => WeaveModule(fileToString(scriptFile), FileHelper.baseName(scriptFile)))
          } else {
            return Right(s"File `$filePath` was not found.")
          }
        } else {
          return Right("Missing script <file-path>")
        }
      }
      
      if (commandLine.hasOption(Options.EVAL)) {
        eval = true
      }
      
      if (commandLine.hasOption(Options.UNTRUSTED_CODE)) {
        maybePrivileges = Some(Seq.empty)
      }
      
      if (commandLine.hasOption(Options.PRIVILEGES)) {
        val privileges = commandLine.getOptionValue(Options.PRIVILEGES)
        maybePrivileges = Some(privileges.split(","))
      }

      if (commandLine.hasOption(Options.LANGUAGE_LEVEL)) {
        val languageLevelStr =  commandLine.getOptionValue(Options.LANGUAGE_LEVEL)
        maybeLanguageLevel = Some(DataWeaveVersion(languageLevelStr))
      }
      
      val commandLineArgs = commandLine.getArgs
      if (commandLineArgs != null) {
        if (commandLineArgs.length == 1) {
          val script = commandLineArgs.head
          scriptToRun = Some(_ => WeaveModule(script, NameIdentifier.ANONYMOUS_NAME.toString()))
        } else if (commandLineArgs.size > 1) {
          val arg = commandLineArgs.head
          return Right(s"Invalid argument $arg")
        }
      }
    } catch {
      case e: Exception =>
        console.error(e.getMessage)
        return Left(HelpCommand(console))
    }

    val paths = if (path.isEmpty) Array[String]() else path.split(File.pathSeparatorChar)
    if (scriptToRun.isEmpty) {
      Right(s"Missing <script-content> or -f <file-path> or --spell ")
    } else {

      val config: WeaveRunnerConfig = WeaveRunnerConfig(paths, eval, scriptToRun.get, dependencyResolver ,params.toMap, inputs.toMap, output, maybePrivileges, maybeLanguageLevel)
      Left(new RunWeaveCommand(config, console))
    }
  }

  private def fileToString(scriptFile: File): String = {
    val source = Source.fromFile(scriptFile, "UTF-8")
    try {
      source.mkString
    } finally {
      source.close()
    }
  }
}
