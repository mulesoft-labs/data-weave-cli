package org.mule.weave.dwnative.cli

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

import org.mule.weave.dwnative.AnsiColor
import org.mule.weave.dwnative.DataWeaveUtils
import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.WeaveInput
import org.mule.weave.dwnative.WeaveProperties
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.module.reader.SourceProvider
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.runtime.utils.AnsiColor.red

import scala.collection.mutable

object DataWeaveCLI extends App {

  {
    val i = new DataWeaveCLIRunner().run(args)
    System.exit(i)
  }

}

class DataWeaveCLIRunner {
  def run(args: Array[String]): Int = {
    val scriptToRun = parse(args)
    scriptToRun match {
      case Right(message) => {
        println(AnsiColor.red(message))
        println(usages())
        -1
      }
      case Left(config) => {
        run(config)
      }
    }
  }

  def parse(args: Array[String]): Either[WeaveRunnerConfig, String] = {
    var i = 0
    //Use the current directory as the path
    var path: String = ""
    var scriptToRun: Option[String] = None
    var output: Option[String] = None
    var debug = false
    var main: Option[String] = None
    val inputs: mutable.Map[String, SourceProvider] = mutable.Map()

    while (i < args.length) {
      args(i) match {
        case "-path" => {
          path = if (i + 1 < args.length) {
            i = i + 1
            args(i)
          } else {
            return Right("Missing path expression")
          }
        }
        case "-verbose" => {
          WeaveProperties.verbose = true
        }
        case "-input" => {
          if (i + 2 < args.length) {
            val input: File = new File(args(i + 2))
            val inputName: String = args(i + 1)
            if (input.exists()) {
              inputs.put(inputName, SourceProvider(input, Charset.defaultCharset(), None))
            } else {
              return Right(red(s"Invalid input file $inputName ${input.getAbsolutePath}."))
            }
          } else {
            return Right(red("Invalid amount of arguments on input."))
          }
          i = i + 2
        }
        case "-output" => {
          output = if (i + 1 < args.length) {
            i = i + 1
            Some(args(i))
          } else {
            return Right("Missing <outputPath>")
          }
        }
        case "-main" => {
          main = if (i + 1 < args.length) {
            i = i + 1
            Some(args(i))
          } else {
            return Right("Missing main name identifier")
          }
        }
        case "-debug" => {
          debug = true
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
      Right(s"Missing <scriptContent> or -main <nameIdentifier>")
    } else {
      Left(WeaveRunnerConfig(paths, debug, scriptToRun, main, inputs.toMap, output))
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
      |dw [-path <weavePath>]? [-input <name> <path>]* [-verbose]? [-output <outputPath>]? [[-main <nameIdentifier>] | <scriptContent>]
      |
      |Arguments Detail:
      |
      | -path    | Path of jars or directories where weave files are being searched
      | -input   | Declares a new input
      | -verbose | Enable Verbose Mode
      | -output  | Specifies output file for the transformation if not standard output will be used
      | -main    | The full qualified name of the mapping to execute
      |
      |
      | Documentation reference:
      |
      | https://docs.mulesoft.com/mule-runtime/4.1/dataweave
    """.stripMargin
  }


  def run(config: WeaveRunnerConfig): Int = {
    val path = config.path.map(new File(_))
    val nativeRuntime = new NativeRuntime(DataWeaveUtils.getLibPathHome(), path)
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

    val out = if (config.outputPath.isDefined) new FileOutputStream(config.outputPath.get) else System.out

    val inputs = if (config.inputs.isEmpty) Array(WeaveInput("payload", SourceProvider(System.in))) else config.inputs.map((input) => WeaveInput(input._1, input._2)).toArray
    val result = nativeRuntime.run(script, inputs, out)
    //load inputs from
    if (result.success()) {

      return 0

    } else {
      println(AnsiColor.red(result.result()))
      -1
    }
  }
}

class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}

case class WeaveRunnerConfig(path: Array[String], debug: Boolean, scriptToRun: Option[String], main: Option[String], inputs: Map[String, SourceProvider], outputPath: Option[String])
