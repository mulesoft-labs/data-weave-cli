package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.WeaveExecutionResult
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.dependencies.DependencyManagerMessageCollector
import org.mule.weave.dwnative.dependencies.DependencyResolutionResult
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.utils.DataWeaveUtils.DW_DEFAULT_INPUT_MIMETYPE_VAR
import org.mule.weave.dwnative.utils.DataWeaveUtils.DW_DEFAULT_OUTPUT_MIMETYPE_VAR
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.structure.KeyValuePair
import org.mule.weave.v2.model.values.KeyValue
import org.mule.weave.v2.model.values.ObjectValue
import org.mule.weave.v2.model.values.StringValue
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.runtime.ExecuteResult
import org.mule.weave.v2.runtime.ScriptingBindings
import sun.misc.Signal
import sun.misc.SignalHandler

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import scala.util.Try

class RunWeaveCommand(val config: WeaveRunnerConfig, console: Console) extends WeaveCommand {
  val weaveUtils = new DataWeaveUtils(console)

  private val DEFAULT_MIME_TYPE: String = "application/json"

  @volatile
  private var keepRunning = true

  def exec(): Int = {
    val path: Array[File] = config.path.map(new File(_))
    val nativeRuntime: NativeRuntime = new NativeRuntime(weaveUtils.getLibPathHome(), path, console)
    config.dependencyResolver.foreach((dep) => {
      val results = dep(nativeRuntime)
      results.foreach((dm) => {
        dm.resolve(
          new DependencyManagerMessageCollector {
            override def onError(id: String, message: String): Unit = {
              console.error(s"${id} ${message}")
            }
          }
        ).foreach((a) => {
          nativeRuntime.addJarToClassPath(a)
        })
      })
    })
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
    var exitCode: Int = ExitCodes.SUCCESS

    val defaultInputType: String = console.envVar(DW_DEFAULT_INPUT_MIMETYPE_VAR).getOrElse(DEFAULT_MIME_TYPE)
    val scriptingBindings: ScriptingBindings = new ScriptingBindings
    if (config.inputs.isEmpty) {
      scriptingBindings.addBinding("payload", console.in, defaultInputType)
    } else {
      config.inputs.foreach((input) => {
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
        val signalHandler: SignalHandler = (sig) => {
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
        case le: Exception => {
          console.error("Error while executing the script:")
          val writer = new StringWriter()
          le.printStackTrace(new PrintWriter(writer))
          console.error(writer.toString)
          exitCode = ExitCodes.FAILURE
        }
      }
    } else {
      val out: OutputStream = if (config.outputPath.isDefined) {
        val outputFile = new File(config.outputPath.get)
        val parentFile: File = outputFile.getParentFile
        if (parentFile == null) {
          console.error(s"Unable to detect container folder for: `${outputFile}`. If relative path used use `./<outputFileName>`")
          return -1
        } else if (!parentFile.exists()) {
          val created = parentFile.mkdirs()
          if (!created) {
            console.error(s"Unable to create output file folder: `${outputFile.getParent}`.")
            return ExitCodes.FAILURE
          }
        }
        new FileOutputStream(outputFile)
      } else {
        console.out
      }
      val defaultOutputType: String = console.envVar(DW_DEFAULT_OUTPUT_MIMETYPE_VAR).getOrElse(DEFAULT_MIME_TYPE)
      val result: WeaveExecutionResult = nativeRuntime.run(module.content, module.nameIdentifier, scriptingBindings, out, defaultOutputType, config.maybePrivileges)
      //load inputs from
      if (result.success()) {
        exitCode = 0
      } else {
        console.error("Error while executing the script:")
        console.error(result.result())
        exitCode = ExitCodes.FAILURE
      }
    }
    exitCode
  }
}

case class WeaveRunnerConfig(path: Array[String],
                             eval: Boolean,
                             scriptToRun: NativeRuntime => WeaveModule,
                             dependencyResolver: Option[(NativeRuntime) => Array[DependencyResolutionResult]],
                             params: Map[String, String],
                             inputs: Map[String, File],
                             outputPath: Option[String],
                             maybePrivileges: Option[Seq[String]])


case class WeaveModule(content: String, nameIdentifier: String)