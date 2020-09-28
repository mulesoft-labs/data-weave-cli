package org.mule.weave.dwnative.cli

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.utils.AnsiColor
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.utils.WeaveProperties
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.runtime.utils.AnsiColor.red
import org.mule.weave.v2.version.ComponentVersion
import sun.misc.Signal
import sun.misc.SignalHandler

import scala.collection.mutable
import scala.io.Source
import scala.util.Try


object DataWeaveCLI extends App {


  {
    val i = new DataWeaveCLIRunner().run(args)
    System.exit(i)
  }

}

class DataWeaveCLIRunner {
  val DW_DEFAULT_INPUT_MIMETYPE_VAR = "DW_DEFAULT_INPUT_MIMETYPE"

  val DW_DEFAULT_OUTPUT_MIMETYPE_VAR = "DW_DEFAULT_OUTPUT_MIMETYPE"

  val DW_CLI_VERSION = ComponentVersion.nativeVersion

  val DW_RUNTIME_VERSION = ComponentVersion.weaveVersion

  def run(args: Array[String]): Int = {
    val scriptToRun = parse(args)
    scriptToRun match {
      case Right(message) if (message.nonEmpty) => {
        println(AnsiColor.red("Parameters configuration error:"))
        println(AnsiColor.red(message))
        println(usages())
        -1
      }
      case Left(config) => {
        run(config)
      }
      case _ => {
        0
      }
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

  def parse(args: Array[String]): Either[WeaveRunnerConfig, String] = {
    var i = 0
    //Use the current directory as the path
    var path: String = ""
    var scriptToRun: Option[String] = None
    var output: Option[String] = None
    var profile = false
    var eval = false
    var cleanCache = false
    var main: Option[String] = None

    val inputs: mutable.Map[String, File] = mutable.Map()


    while (i < args.length) {
      args(i) match {
        case "-p" | "--path" => {
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
        case "-v" | "--verbose" => {
          WeaveProperties.verbose = true
        }
        case "-h" | "--help" => {
          println(usages())
          return Right("")
        }
        case "--version" => {
          println(" - DataWeave Command Line : V" + DW_CLI_VERSION)
          println(" - DataWeave Runtime: V" + DW_RUNTIME_VERSION)
          return Right("")
        }
        case "--update-grimoires" => {
          println("Updating grimoires")
          updateGrimoires()
          return Right("")
        }
        case "--add-wizard" => {
          if (i + 1 < args.length) {
            i = i + 1
            val wizardName = args(i)
            println(s"Downloading Grimoire From The Wise: `${wizardName}`.")
            cloneGrimoire(wizardName)
            return Right("")
          } else {
            return Right("Missing <outputPath>")
          }
        }
        case "--clean-cache" => {
          cleanCache = true
        }
        case "--spell" => {
          if (i + 1 < args.length) {
            i = i + 1
            val spell = args(i)
            val wizard = if (spell.contains("/")) {
              spell.split("/").head
            } else {
              null
            }
            val spellName = if (spell.contains("/")) {
              spell.split("/")(1)
            } else {
              spell
            }

            var wizardGrimoire = grimoireFolder(wizard)
            if (!wizardGrimoire.exists()) {
              cloneGrimoire(wizard)
            }
            wizardGrimoire = grimoireFolder(wizard)
            val wizardName = if(wizard == null) "Weave" else wizard
            if (!wizardGrimoire.exists()) {

              return Right(s"[ERROR] Unable to get Wise `$wizardName's` Grimoire.")

            }

            val spellFolder = new File(wizardGrimoire, spellName)
            if (!spellFolder.exists()) {
              updateGrimoire(wizardGrimoire)
            }

            if (!spellFolder.exists()) {
              return Right(s"[ERROR] Unable find ${spellName} in Wise `${wizardName}'s` Grimoire.")
            }

            val srcFolder = new File(spellFolder, "src")
            val mainFile = new File(srcFolder, "Main.dwl")
            if (!mainFile.isFile) {
              return Right(s"[ERROR] Unable find `Main.dwl` in the spell: `${spellName}` inside Wise `${wizardName}'s` Grimoire.")
            }
            if (path.isEmpty) {
              path = srcFolder.getAbsolutePath
            } else {
              path = path + File.pathSeparator + srcFolder.getAbsolutePath
            }
            scriptToRun = Some(fileToString(mainFile))
          } else {
            return Right("Missing <spellName>")
          }
        }
        case "-i" | "--input" => {
          if (i + 2 < args.length) {
            val input: File = new File(args(i + 2))
            val inputName: String = args(i + 1)
            if (input.exists()) {
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
        case "-main" | "-m" => {
          if (i + 1 < args.length) {
            i = i + 1
            main = Some(args(i))
          } else {
            return Right("Missing main name identifier")
          }
        }
        case "-f" | "--file" => {
          if (i + 1 < args.length) {
            i = i + 1
            val scriptFile = new File(args(i))
            if (scriptFile.exists()) {
              scriptToRun = Some(fileToString(scriptFile))
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
        case scriptPath if (i + 1 == args.length) => {
          scriptToRun = Some(scriptPath)
        }
        case arg => {
          return Right(s"Invalid argument ${arg}")
        }
      }
      i = i + 1
    }

    val paths = if (path.isEmpty) Array[String]() else path.split(File.pathSeparatorChar)
    if (scriptToRun.isEmpty && main.isEmpty) {
      Right(s"Missing <scriptContent> or -m <nameIdentifier> of -f <filePath> or --spell ")
    } else {
      Left(WeaveRunnerConfig(paths, profile, eval, cleanCache, scriptToRun, main, inputs.toMap, output))
    }
  }

  def usages(): String = {
    """
      |
      |.........................................................................
      |.%%%%%....%%%%...%%%%%%...%%%%...%%...%%..%%%%%%...%%%%...%%..%%..%%%%%%.
      |.%%..%%..%%..%%....%%....%%..%%..%%...%%..%%......%%..%%..%%..%%..%%.....
      |.%%..%%..%%%%%%....%%....%%%%%%..%%.%.%%..%%%%....%%%%%%..%%..%%..%%%%...
      |.%%..%%..%%..%%....%%....%%..%%..%%%%%%%..%%......%%..%%...%%%%...%%.....
      |.%%%%%...%%..%%....%%....%%..%%...%%.%%...%%%%%%..%%..%%....%%....%%%%%%.
      |.........................................................................
      |
      |
      |Usage:
      |
      |dw [-p <weavePath>]? [-i <name> <path>]* [-v]? [-o <outputPath>]? [[-f <filePath>] | [-m <nameIdentifier>] | <scriptContent>]
      |
      |Arguments Detail:
      |
      | --spell | Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.
      | --update-grimoires | Update all wizard grimoires
      | --add-wizard    | Downloads wizard grimoire so that its spell are accessible
      | --path or -p    | Path of jars or directories where weave files are being searched.
      | --input or -i   | Declares a new input.
      | --verbose or -v | Enable Verbose Mode.
      | --output or -o  | Specifies output file for the transformation if not standard output will be used.
      | --main or -m    | The full qualified name of the mapping to be execute.
      | --file or -f     | Path to the file
      | --eval          | Evaluates the script instead of writing it
      | --version       | The version of the CLI and Runtime
      | --clean-cache   | Cleans the cache where all artifacts are being downloaded this force to download all artifacts every time
      |
      | Example:
      |
      | dw -i payload <fullpathToUser.json> "output application/json --- payload filter (item) -> item.age > 17"
      |
      | Documentation reference:
      |
      | https://docs.mulesoft.com/mule-runtime/4.3/dataweave
    """.stripMargin
  }

  def deleteDirectory(directoryToBeDeleted: File): Boolean = {
    val allContents = directoryToBeDeleted.listFiles
    if (allContents != null) {
      for (file <- allContents) {
        deleteDirectory(file)
      }
    }
    directoryToBeDeleted.delete
  }


  def run(config: WeaveRunnerConfig): Int = {
    val path = config.path.map(new File(_))

    val cacheDirectory = DataWeaveUtils.getCacheHome()
    if(config.cleanCache){
      deleteDirectory(cacheDirectory)
      cacheDirectory.mkdirs()
    }
    val nativeRuntime = new NativeRuntime(cacheDirectory, DataWeaveUtils.getLibPathHome(), path, Executors.newCachedThreadPool())

    val script: String = if (config.main.isDefined) {
      val mainScriptName = config.main.get
      val maybeString = nativeRuntime.getResourceContent(NameIdentifier(mainScriptName))
      if (maybeString.isDefined) {
        maybeString.get
      } else {
        println(AnsiColor.red(s"[ERROR] Unable to resolve `${mainScriptName}` in the specified classpath."))
        return -1; //ERROR
      }
    } else {
      config.scriptToRun.get
    }

    val defaultInputType = Option(System.getenv(DW_DEFAULT_INPUT_MIMETYPE_VAR)).getOrElse("application/json")
    val scriptingBindings = new ScriptingBindings
    if (config.inputs.isEmpty) {
      scriptingBindings.addBinding("payload", System.in, defaultInputType)
    } else {
      config.inputs.foreach((input) => {
        scriptingBindings.addBinding(input._1, input._2, getMimeTypeByFileExtension(input._2))
      })
    }

    if (config.eval) {
      try {
        //We need this to be able to handle the ctrl+c
        val signalHandler: SignalHandler = (sig) => {
          System.exit(sig.getNumber)
        }
        Signal.handle(new Signal("INT"), signalHandler)
        Signal.handle(new Signal("TERM"), signalHandler)

        val result = nativeRuntime.eval(script, scriptingBindings, config.profile)
        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            Try(result.close())
            System.out.println("Thanks for using DW. Have a nice day!")
          }
        })
        System.out.println("\nPress 'ctrl'+c to stop the process.")
        while (true) {
          Thread.sleep(1000)
        }
        0
      } catch {
        case le: Exception => {
          println(AnsiColor.red("Error while executing the script:"))
          val writer = new StringWriter()
          le.printStackTrace(new PrintWriter(writer))
          println(AnsiColor.red(writer.toString))
          -1
        }
      }
    } else {
      val out = if (config.outputPath.isDefined) new FileOutputStream(config.outputPath.get) else System.out
      val defaultOutputType = Option(System.getenv(DW_DEFAULT_OUTPUT_MIMETYPE_VAR)).getOrElse("application/json");
      val result = nativeRuntime.run(script, scriptingBindings, out, defaultOutputType, config.profile)
      //load inputs from
      if (result.success()) {
        0
      } else {
        println(AnsiColor.red("Error while executing the script:"))
        println(AnsiColor.red(result.result()))
        -1
      }
    }
  }

  def getMimeTypeByFileExtension(file: File): Option[String] = {
    val extension = file.getName.lastIndexOf('.')
    if (extension > 0) {
      val ext = file.getName.substring(extension)
      DataFormatManager.byExtension(ext)(EvaluationContext()).map(_.defaultMimeType.toString())
    } else {
      None
    }
  }


  def grimoireFolder(wizard: String): File = {
    val grimoiresFolder = grimoiresFolders
    new File(grimoiresFolder, grimoireName(wizard))
  }

   def grimoiresFolders: File = {
    new File(DataWeaveUtils.getDWHome(), "grimoires")
  }

  def cloneGrimoire(wizard: String): Unit = {
    println(s"Fetching `${wizard}'s` Grimoire.")
    val url: String = buildRepoUrl(wizard)
    val processBuilder = new ProcessBuilder("git", "clone", url, grimoireFolder(wizard).getAbsolutePath)
    processBuilder.inheritIO()
    processBuilder.start().waitFor()
  }

  def updateGrimoires(): Unit = {
    val grimoires = grimoiresFolders.listFiles()
    grimoires.foreach((grimoire) => {
      updateGrimoire(grimoire)
    })
  }

  def updateGrimoire(grimoire: File): Int = {
    val processBuilder = new ProcessBuilder("git", "pull")
    processBuilder.directory(grimoire)
    processBuilder.inheritIO()
    processBuilder.start().waitFor()

  }

  def buildRepoUrl(user: String): String = {
    val domain = if (user == null) "mulesoft-labs" else user
    val repo = grimoireName(user)
    val url = s"https://github.com/${domain}/${repo}.git"
    url
  }

  def grimoireName(user: String): String = {
    if (user == null)
      "data-weave-grimoire"
    else
      s"${user}-data-weave-grimoire"
  }

}

class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}

case class WeaveRunnerConfig(path: Array[String], profile: Boolean, eval: Boolean, cleanCache:Boolean, scriptToRun: Option[String], main: Option[String], inputs: Map[String, File], outputPath: Option[String])

