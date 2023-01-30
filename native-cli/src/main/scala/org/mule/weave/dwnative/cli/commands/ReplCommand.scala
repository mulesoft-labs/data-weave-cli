package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.WeaveExecutionResult
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.dependencies.DependencyResolutionResult
import org.mule.weave.dwnative.dependencies.ResolutionErrorHandler
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.dwnative.utils.DataWeaveUtils.DW_DEFAULT_OUTPUT_MIMETYPE_VAR
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.structure.KeyValuePair
import org.mule.weave.v2.model.values.KeyValue
import org.mule.weave.v2.model.values.ObjectValue
import org.mule.weave.v2.model.values.StringValue
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.utils.DataWeaveVersion

import java.io.File
import java.util.Scanner

class ReplCommand(config: ReplConfiguration, console: Console) extends WeaveCommand {

  val weaveUtils = new DataWeaveUtils(console)

  override def exec(): Int = {
    var exitCode = ExitCodes.SUCCESS
    val path: Array[File] = config.path.map(new File(_))
    val nativeRuntime: NativeRuntime = new NativeRuntime(weaveUtils.getLibPathHome(), path, console, config.maybeLanguageLevel)
    config.dependencyResolver.foreach((dep) => {
      val results = dep(nativeRuntime)
      results.foreach((dm) => {
        val resolvedDependencies = dm.resolve(
          new ResolutionErrorHandler {
            override def onError(id: String, message: String): Unit = {
              console.error(s"Unable to resolve: `${id}`. Reason: ${message}")
              exitCode = ExitCodes.FAILURE
            }
          }
        )
        if (resolvedDependencies.isEmpty) {
          console.error(s"${dm.id} didn't resolve to any artifact.")
          exitCode = ExitCodes.FAILURE
        } else {
          resolvedDependencies.foreach((a) => {
            nativeRuntime.addJarToClassPath(a)
          })
        }
      })
    })
    if (exitCode == ExitCodes.SUCCESS) {
      doRun(config, nativeRuntime)
    } else {
      exitCode
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

  def doRun(config: ReplConfiguration, nativeRuntime: NativeRuntime): Int = {
    val scriptingBindings: ScriptingBindings = new ScriptingBindings
    config.inputs.foreach((input) => {
      scriptingBindings.addBinding(input._1, input._2, getMimeTypeByFileExtension(input._2))
    })
    val value: Array[KeyValuePair] = config.params.toSeq.map(prop =>
      KeyValuePair(KeyValue(prop._1), StringValue(prop._2))
    ).toArray

    val params = ObjectValue(value)
    scriptingBindings.addBinding("params", params)

    val input = new Scanner(System.in)
    System.out.println("DW REPL")

    var continue = true
    while (continue) {
      System.out.print(">>> ")
      var str = input.nextLine()
      while (str.endsWith("\\")) {
        str = str.substring(0, str.length - 1) + "\n" + input.nextLine()
      }
      if (str.equals("quit()")) {
        continue = false
      } else {
        val defaultOutputType: String = console.envVar(DW_DEFAULT_OUTPUT_MIMETYPE_VAR).getOrElse("application/dw")
        val result: WeaveExecutionResult = nativeRuntime.run(str, NameIdentifier.ANONYMOUS_NAME.name, scriptingBindings, console.out, defaultOutputType, config.maybePrivileges)
        //load inputs from
        if (!result.success()) {
          System.out.println(result.result())
        } else {
          System.out.println("")
        }
      }
    }
    ExitCodes.SUCCESS
  }
}

case class ReplConfiguration(path: Array[String],
                             dependencyResolver: Option[(NativeRuntime) => Array[DependencyResolutionResult]],
                             params: Map[String, String],
                             inputs: Map[String, File],
                             maybePrivileges: Option[Seq[String]],
                             maybeLanguageLevel: Option[DataWeaveVersion])
