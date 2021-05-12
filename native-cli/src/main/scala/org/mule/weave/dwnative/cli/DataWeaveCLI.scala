package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.WeaveExecutionResult
import org.mule.weave.dwnative.utils.AnsiColor
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.utils.WeaveProperties
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.io.FileHelper
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.values.StringValue
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.runtime.ExecuteResult
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.runtime.utils.AnsiColor.red
import org.mule.weave.v2.version.ComponentVersion
import sun.misc.Signal
import sun.misc.SignalHandler

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.Try


object DataWeaveCLI extends App {


  {
    val i = new DataWeaveCLIRunner().run(args)
    System.exit(i)
  }

}

class DataWeaveCLIRunner {
  val DW_DEFAULT_INPUT_MIMETYPE_VAR: String = "DW_DEFAULT_INPUT_MIMETYPE"
  val DW_DEFAULT_OUTPUT_MIMETYPE_VAR: String = "DW_DEFAULT_OUTPUT_MIMETYPE"
  private val DATA_WEAVE_GRIMOIRE_FOLDER = "data-weave-grimoire"

  val DW_CLI_VERSION: String = ComponentVersion.nativeVersion
  val DW_RUNTIME_VERSION: String = ComponentVersion.weaveVersion


  @transient
  var keepRunning = true
  val monitor = new Object()


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
    var scriptToRun: Option[(NativeRuntime) => WeaveModule] = None
    var output: Option[String] = None
    var profile = false
    var eval = false
    var watch = false
    val filesToWatch: ArrayBuffer[File] = ArrayBuffer()
    var cleanCache = false
    var remoteDebug = false
    var telemetry = false

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
        case "--watch" => {
          watch = true
        }
        case "--list-spells" => {
          val builder: StringBuilder = listSpells()
          println(builder)
          return Right("")
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
            val spellName = if (spell.contains("/")) {
              spell.split("/")(1)
            } else {
              spell
            }
            val lastUpdate = hoursSinceLastUpdate()
            //Update grimoires every day
            if (lastUpdate > 24) {
              updateGrimoires()
            }

            var wizardGrimoire = grimoireFolder(wizard)
            if (!wizardGrimoire.exists()) {
              cloneGrimoire(wizard)
            }
            wizardGrimoire = grimoireFolder(wizard)
            val wizardName = if (wizard == null) "Weave" else wizard
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
            scriptToRun = Some((_) => {
              WeaveModule(fileToString(mainFile), "Main")
            })
          } else {
            return Right("Missing <spellName>")
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
        case "--telemetry" => {
          telemetry = true
        }
        case "-main" | "-m" => {
          if (i + 1 < args.length) {
            i = i + 1
            scriptToRun = Some((nativeRuntime) => {
              val mainScriptName = args(i)
              val maybeString = nativeRuntime.getResourceContent(NameIdentifier(mainScriptName))
              if (maybeString.isDefined) {
                WeaveModule(maybeString.get, args(i))
              } else {
                println(AnsiColor.red(s"[ERROR] Unable to resolve `${mainScriptName}` in the specified classpath."))
                WeaveModule("", args(i))
              }
            })
          } else {
            return Right("Missing main name identifier")
          }
        }
        case "--remote-debug" => {
          remoteDebug = true
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
      Left(WeaveRunnerConfig(paths, profile, eval, cleanCache, scriptToRun.get, properties.toMap, inputs.toMap, output, filesToWatch, watch, remoteDebug, telemetry))
    }
  }


  private def listSpells(): StringBuilder = {
    val builder = new StringBuilder()
    builder.append("Spells:\n")
    val grimoires = grimoiresFolders()
    val grimoiresDirs = grimoires.listFiles()
    if (grimoiresDirs != null) {
      grimoiresDirs.foreach((g) => {
        val name = if (g.getName.equals(DATA_WEAVE_GRIMOIRE_FOLDER)) "" else g.getName + "/"
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
    builder
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
      | --watch            | Keep the cli up and watch the used files for modifications and re execute
      | --list-spells      | List all the available spells
      | --spell or -s      | Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.
      | --update-grimoires | Update all wizard grimoires
      | --add-wizard       | Downloads wizard grimoire so that its spell are accessible
      | --path             | Path of jars or directories where weave files are being searched.
      | --prop or -p       | Property to be passed.
      | --input or -i      | Declares a new input.
      | --verbose or -v    | Enable Verbose Mode.
      | --output or -o     | Specifies output file for the transformation if not standard output will be used.
      | --main or -m       | The full qualified name of the mapping to be execute.
      | --file or -f       | Path to the file
      | --eval             | Evaluates the script instead of writing it
      | --version          | The version of the CLI and Runtime
      | --clean-cache      | Cleans the cache where all artifacts are being downloaded this force to download all artifacts every time
      | --remote-debug     | Enables remote debugging
      | --telemetry        | Enables telemetry reporting
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
    val path: Array[File] = config.path.map(new File(_))
    val cacheDirectory: File = DataWeaveUtils.getCacheHome()
    if (config.cleanCache) {
      deleteDirectory(cacheDirectory)
      cacheDirectory.mkdirs()
    }
    val nativeRuntime: NativeRuntime = new NativeRuntime(cacheDirectory, DataWeaveUtils.getLibPathHome(), path, Executors.newCachedThreadPool())

    if (config.watch) {
      clearConsole()
      val fileWatcher = FileWatcher(config.filesToWatch)
      fileWatcher.addListener((_) => {
        clearConsole()
        keepRunning = false
        monitor.synchronized({
          monitor.notifyAll()
        })
      })
      fileWatcher.startWatching()
      doRun(config, nativeRuntime)
    } else {
      doRun(config, nativeRuntime)
    }
  }


  def clearConsole(): Unit = {
    Try({
      if (System.getProperty("os.name").contains("Windows")) {
        new ProcessBuilder("cmd", "/c", "cls").inheritIO.start.waitFor
      }
      else {
        System.out.print("\u001b\u0063")
      }
    })
  }

  def doRun(config: WeaveRunnerConfig, nativeRuntime: NativeRuntime): Int = {
    var exitCode: Int = 0
    do {
      val defaultInputType: String = Option(System.getenv(DW_DEFAULT_INPUT_MIMETYPE_VAR)).getOrElse("application/json")
      val scriptingBindings: ScriptingBindings = new ScriptingBindings
      if (config.inputs.isEmpty) {
        scriptingBindings.addBinding("payload", System.in, defaultInputType)
      } else {
        config.inputs.foreach((input) => {
          scriptingBindings.addBinding(input._1, input._2, getMimeTypeByFileExtension(input._2))
        })
      }

      config.properties.foreach((prop) => {
        scriptingBindings.addBinding(prop._1, StringValue(prop._2))
      })

      val module: WeaveModule = config.scriptToRun(nativeRuntime)
      if (config.eval) {
        keepRunning = true
        try {
          //We need this to be able to handle the ctrl+c
          val signalHandler: SignalHandler = (sig) => {
            System.exit(sig.getNumber)
          }
          Signal.handle(new Signal("INT"), signalHandler)
          Signal.handle(new Signal("TERM"), signalHandler)
          val result: ExecuteResult = nativeRuntime.eval(module.content, scriptingBindings, module.nameIdentifier, config.profile, config.remoteDebug, config.telemetry)
          Runtime.getRuntime.addShutdownHook(new Thread() {
            override def run(): Unit = {
              Try(result.close())
              System.out.println("Thanks for using DW. Have a nice day!")
            }
          })
          System.out.println("\nPress 'ctrl'+c to stop the process.")
          while (keepRunning) {
            Thread.sleep(100)
          }
          Try(result.close())
        } catch {
          case le: Exception => {
            println(AnsiColor.red("Error while executing the script:"))
            val writer = new StringWriter()
            le.printStackTrace(new PrintWriter(writer))
            println(AnsiColor.red(writer.toString))
            exitCode = -1
          }
        }
      } else {
        val out: OutputStream = if (config.outputPath.isDefined) {
          val outputFile = new File(config.outputPath.get)
          val parentFile: File = outputFile.getParentFile
          if (parentFile == null) {
            println(AnsiColor.red(s"Unable to detect container folder for: `${outputFile}`. If relative path used use `./<outputFileName>`"))
            return -1
          } else if (!parentFile.exists()) {
            val created = parentFile.mkdirs()
            if (!created) {
              println(AnsiColor.red(s"Unable to create output file folder: `${outputFile.getParent}`."))
              return -1
            }
          }
          new FileOutputStream(outputFile)
        } else {
          System.out
        }
        val defaultOutputType = Option(System.getenv(DW_DEFAULT_OUTPUT_MIMETYPE_VAR)).getOrElse("application/json")
        val result: WeaveExecutionResult = nativeRuntime.run(module.content, module.nameIdentifier, scriptingBindings, Some(out), defaultOutputType, config.profile, config.remoteDebug, config.telemetry)
        //load inputs from
        if (result.success()) {
          exitCode = 0
        } else {
          println(AnsiColor.red("Error while executing the script:"))
          println(AnsiColor.red(result.result()))
          exitCode = -1
        }

        if (config.watch) {
          monitor.synchronized({
            monitor.wait()
          })
        }
      }
    } while (config.watch)
    exitCode
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

  def hoursSinceLastUpdate(): Int = {
    val lastModified = lastUpdatedMarkFile().lastModified()
    val millis = System.currentTimeMillis() - lastModified
    (millis / (1000 * 60 * 20)).asInstanceOf[Int]
  }

  def grimoireFolder(wizard: String): File = {
    val grimoiresFolder = grimoiresFolders()
    new File(grimoiresFolder, grimoireName(wizard))
  }

  def grimoiresFolders(): File = {
    val file = new File(DataWeaveUtils.getDWHome(), "grimoires")
    if (!file.exists()) {
      file.mkdirs()
    }
    file
  }

  def cloneGrimoire(wizard: String): Unit = {
    val wizardName = if (wizard == null) "DW" else wizard
    val wizardFolder = grimoireFolder(wizard)
    if (wizardFolder.exists()) {
      println(red(s"Wizard `${wizard}` was already added."))
    } else {
      println(s"Fetching `$wizardName's` Grimoire.")
      val url: String = buildRepoUrl(wizard)
      val processBuilder = new ProcessBuilder("git", "clone", url, wizardFolder.getAbsolutePath)
      processBuilder.inheritIO()
      processBuilder.start().waitFor()
    }
  }

  def updateLastUpdateTimeStamp(): Boolean = {
    val lastUpdate: File = lastUpdatedMarkFile()
    lastUpdate.setLastModified(System.currentTimeMillis())
  }

  private def lastUpdatedMarkFile() = {
    val lastUpdate = new File(grimoiresFolders(), "lastUpdate.txt")
    if (!lastUpdate.exists()) {
      Files.write(lastUpdate.toPath, "LAST UPDATE".getBytes("UTF-8"))
    }
    lastUpdate
  }

  def updateGrimoires(): Unit = {
    updateLastUpdateTimeStamp()
    val grimoires = grimoiresFolders().listFiles()
    grimoires.foreach((grimoire) => {
      //If it is not a directory it can be the lastUpdate.txt
      if (grimoire.isDirectory) {
        updateGrimoire(grimoire)
      }
    })
  }

  def updateGrimoire(grimoire: File): Int = {
    println(s"Updating `${grimoire.getName}'s` Grimoire.")
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
      DATA_WEAVE_GRIMOIRE_FOLDER
    else
      s"${user}-$DATA_WEAVE_GRIMOIRE_FOLDER"
  }

}

class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}

case class WeaveRunnerConfig(path: Array[String],
                             profile: Boolean,
                             eval: Boolean,
                             cleanCache: Boolean,
                             scriptToRun: (NativeRuntime) => WeaveModule,
                             properties: Map[String, String],
                             inputs: Map[String, File],
                             outputPath: Option[String],
                             filesToWatch: Seq[File],
                             watch: Boolean,
                             remoteDebug: Boolean,
                             telemetry: Boolean
                            )

case class WeaveModule(content: String, nameIdentifier: String)
