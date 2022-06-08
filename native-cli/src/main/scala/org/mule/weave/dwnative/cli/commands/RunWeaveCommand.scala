package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.WeaveFailureResult
import org.mule.weave.dwnative.WeaveSuccessResult
import org.mule.weave.dwnative.cli.EnvironmentVariableProvider
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.structure.KeyValuePair
import org.mule.weave.v2.model.values.{KeyValue, ObjectValue, StringValue}
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.runtime.{ExecuteResult, ScriptingBindings}
import sun.misc.{Signal, SignalHandler}

import java.io.{File, FileOutputStream, OutputStream, PrintWriter, StringWriter}
import scala.util.Try

class RunWeaveCommand(val config: WeaveRunnerConfig, console: Console, envVarProvider: EnvironmentVariableProvider) extends WeaveCommand {
  val weaveUtils = new DataWeaveUtils(console, envVarProvider)

  private val DEFAULT_MIME_TYPE: String = "application/json"

  @volatile
  private var keepRunning = true

  def exec(): Int = {
    val path: Array[File] = config.path.map(new File(_))
    val nativeRuntime: NativeRuntime = new NativeRuntime(weaveUtils.getLibPathHome(), path, console, envVarProvider)
    doRun(config, nativeRuntime)
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

  def doRun(config: WeaveRunnerConfig, nativeRuntime: NativeRuntime): Int = {
    var exitCode: Int = 0

    val defaultInputType: String = envVarProvider.envVar(EnvironmentVariableProvider.DW_DEFAULT_INPUT_MIMETYPE_VAR).getOrElse(DEFAULT_MIME_TYPE)
    val scriptingBindings: ScriptingBindings = new ScriptingBindings
    if (config.inputs.isEmpty) {
      scriptingBindings.addBinding("payload", console.in, defaultInputType)
    } else {
      config.inputs.foreach(input => {
        scriptingBindings.addBinding(input._1, input._2, getMimeTypeByFileExtension(input._2))
      })
    }

    val value = config.params.toSeq.map(prop =>
      KeyValuePair(KeyValue(prop._1), StringValue(prop._2))
    ).to

    val params = ObjectValue(value)
    scriptingBindings.addBinding("params", params)

    val module: WeaveModule = config.scriptToRun(nativeRuntime)
    if (config.eval) {
      keepRunning = true
      try {
        //We need this to be able to handle the ctrl+c
        val signalHandler: SignalHandler = sig => {
          System.exit(sig.getNumber)
        }
        Signal.handle(new Signal("INT"), signalHandler)
        Signal.handle(new Signal("TERM"), signalHandler)
        val result: ExecuteResult = nativeRuntime.eval(module.content, scriptingBindings, module.nameIdentifier, config.maybePrivileges)
        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            Try(result.close())
            console.info("Thanks for using DW. Have a nice day!")
          }
        })
        console.info("\nPress 'ctrl'+c to stop the process.")
        while (keepRunning) {
          Thread.sleep(100)
        }
        Try(result.close())
      } catch {
        case le: Exception =>
          console.error("Error while executing the script:")
          val writer = new StringWriter()
          le.printStackTrace(new PrintWriter(writer))
          console.error(writer.toString)
          exitCode = -1
      }
    } else {
      val defaultOutputType: String = envVarProvider.envVar(EnvironmentVariableProvider.DW_DEFAULT_OUTPUT_MIMETYPE_VAR).getOrElse(DEFAULT_MIME_TYPE)
      val target: Option[OutputStream] = if (config.outputPath.isDefined) {
        val outputFile = new File(config.outputPath.get)
        val parentFile: File = outputFile.getParentFile
        if (parentFile == null) {
          console.error(s"Unable to detect container folder for: `$outputFile`. If relative path used use `./<outputFileName>`")
          return -1
        } else if (!parentFile.exists()) {
          val created = parentFile.mkdirs()
          if (!created) {
            console.error(s"Unable to create output file folder: `${outputFile.getParent}`.")
            return -1
          }
        }
        Some(new FileOutputStream(outputFile))
      } else {
        if (console.supportsStreaming) Some(console.out) else None  
      }
      val result = nativeRuntime.run(module.content, module.nameIdentifier, scriptingBindings, target, defaultOutputType, config.maybePrivileges)
      result match {
        case success: WeaveSuccessResult =>
          if (config.outputPath.isEmpty && !console.supportsStreaming) {
            console.printResult(success)
          }
          exitCode = 0
        case failure: WeaveFailureResult =>
          console.printResult(failure)
          exitCode = -1
      }
    }
    exitCode
  }
}

case class WeaveRunnerConfig(path: Array[String],
                             eval: Boolean,
                             scriptToRun: NativeRuntime => WeaveModule,
                             params: Map[String, String],
                             inputs: Map[String, File],
                             outputPath: Option[String],
                             maybePrivileges: Option[Seq[String]],
                             coloring: Boolean)

case class WeaveModule(content: String, nameIdentifier: String)
