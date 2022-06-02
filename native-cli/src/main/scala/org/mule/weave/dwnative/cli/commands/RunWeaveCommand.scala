package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.utils.DataWeaveUtils.{DW_DEFAULT_INPUT_MIMETYPE_VAR, DW_DEFAULT_OUTPUT_MIMETYPE_VAR}
import org.mule.weave.dwnative.{NativeRuntime, WeaveExecutionResult, WeaveSuccessResult}
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.structure.KeyValuePair
import org.mule.weave.v2.model.values.{KeyValue, ObjectValue, StringValue}
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.runtime.{ExecuteResult, ScriptingBindings}
import sun.misc.{Signal, SignalHandler}

import java.io.{ByteArrayOutputStream, File, FileOutputStream, OutputStream, PrintWriter, StringWriter}
import scala.util.Try

class RunWeaveCommand(val config: WeaveRunnerConfig, console: Console) extends WeaveCommand {
  val weaveUtils = new DataWeaveUtils(console)

  private val DEFAULT_MIME_TYPE: String = "application/json"

  @volatile
  private var keepRunning = true

  def exec(): Int = {
    val path: Array[File] = config.path.map(new File(_))
    val nativeRuntime: NativeRuntime = new NativeRuntime(weaveUtils.getLibPathHome(), path, console)
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
          exitCode = -1
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
            return -1
          }
        }
        new FileOutputStream(outputFile)
      } else {
        console.out
      }
      val defaultOutputType: String = console.envVar(DW_DEFAULT_OUTPUT_MIMETYPE_VAR).getOrElse(DEFAULT_MIME_TYPE)
      val result: WeaveExecutionResult = nativeRuntime.run(module.content, module.nameIdentifier, scriptingBindings, out, defaultOutputType, config.maybePrivileges)
      if (result.success()) {
        out match {
          case byteArrayOutputStream: ByteArrayOutputStream => {
            val extension = result.extension().getOrElse("json")
            val successResult = result.asInstanceOf[WeaveSuccessResult]
            var charset = successResult.charset
            if (charset == null || charset.isEmpty) {
              charset = "UTF-8"
            }
            val transformationResult = console.highLight(byteArrayOutputStream.toString(charset), extension)
            console.info(transformationResult)
          }
          case _ =>
        }
        exitCode = 0
      } else {
        console.error("Error while executing the script:")
        console.error(result.result())
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
