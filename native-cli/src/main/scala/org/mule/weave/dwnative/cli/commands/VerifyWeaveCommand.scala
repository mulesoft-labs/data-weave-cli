package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.CompilationException
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.utils.DataWeaveVersion

class VerifyWeaveCommand(val config: WeaveVerifyConfig, console: Console) extends WeaveCommand {
  val weaveUtils = new DataWeaveUtils(console)

  def exec(): Int = {
    var exitCode: Int = ExitCodes.SUCCESS
    val nativeRuntime: NativeRuntime = new NativeRuntime(weaveUtils.getLibPathHome(), Array(), console, config.maybeLanguageLevel)
    val weaveModule = config.scriptToRun(nativeRuntime)
    try {
      console.info(s"Compiling `${weaveModule.nameIdentifier}`...")
      val bindings = ScriptingBindings()
      config.inputs.foreach((input) => {
        bindings.addBinding(input, "")
      })
      nativeRuntime.compileScript(weaveModule.content, bindings, NameIdentifier(weaveModule.nameIdentifier), "")
      console.info(s"No errors found.")
    } catch {
      case ce: CompilationException => {
        val ee = ce.getErrorMessages()
        ee.foreach((em) => {
          console.error(em)
        })
        console.info(s"${ee.length} errors found")
        exitCode = ExitCodes.FAILURE
      }
    }
    exitCode
  }
}

case class WeaveVerifyConfig(
                              scriptToRun: NativeRuntime => WeaveModule,
                              maybeLanguageLevel: Option[DataWeaveVersion],
                              inputs: Array[String]
                            )

