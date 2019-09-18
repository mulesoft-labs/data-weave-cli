package org.mule.weave.dwnative

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter

import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.model.ServiceManager
import org.mule.weave.v2.model.service.StdOutputLoggingService
import org.mule.weave.v2.model.values.BinaryValue
import org.mule.weave.v2.module.reader.AutoPersistedOutputStream
import org.mule.weave.v2.module.reader.DefaultAutoPersistedOutputStream
import org.mule.weave.v2.module.reader.SourceProvider
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.exception.LocatableException
import org.mule.weave.v2.parser.phase.CompilationException
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.runtime.DataWeaveScriptingEngine
import org.mule.weave.v2.runtime.DynamicModuleComponentFactory
import org.mule.weave.v2.runtime.ExecuteResult
import org.mule.weave.v2.runtime.InputType
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver

class NativeRuntime(libDir: File, path: Array[File]) {

  private val pathBasedResourceResolver = PathBasedResourceResolver(path ++ Option(libDir.listFiles()).getOrElse(new Array[File](0)))

  private val weaveScriptingEngine = DataWeaveScriptingEngine(DynamicModuleComponentFactory(ClassLoaderWeaveResourceResolver(), () => pathBasedResourceResolver, systemFirst = true))

  def getResourceContent(ni: NameIdentifier): Option[String] = {
    pathBasedResourceResolver.resolve(ni).map(_.content())
  }

  def run(script: String, inputs: ScriptingBindings): WeaveExecutionResult = {
    run(script, inputs, new DefaultAutoPersistedOutputStream())
  }

  def run(script: String, inputs: ScriptingBindings, out: OutputStream, defaultOutputMimeType: String = "application/json"): WeaveExecutionResult = {
    try {
      val dataWeaveScript = weaveScriptingEngine.compile(script, NameIdentifier.ANONYMOUS_NAME, inputs.entries().map((wi) => new InputType(wi, None)).toArray)
      val serviceManager = ServiceManager(StdOutputLoggingService)
      val result = dataWeaveScript.write(inputs, serviceManager, dataWeaveScript.declaredOutput().getOrElse(defaultOutputMimeType), Some(out))
      WeaveSuccessResult(out, result.getCharset().name())
    } catch {
      case cr: CompilationException => {
        WeaveFailureResult(cr.getMessage())
      }
      case le: LocatableException => {
        WeaveFailureResult(le.getMessage() + " at:\n" + le.location.locationString)
      }
      case le: Exception => {
        val writer = new StringWriter()
        le.printStackTrace()
        le.printStackTrace(new PrintWriter(writer))
        WeaveFailureResult("Internal error : " + le.getClass.getName + " : " + le.getMessage + "\n" + writer.toString)
      }
    }
  }

  def eval(script: String, inputs: ScriptingBindings): ExecuteResult = {
    val dataWeaveScript = weaveScriptingEngine.compile(script, NameIdentifier.ANONYMOUS_NAME, inputs.entries().map((wi) => new InputType(wi, None)).toArray)
    val serviceManager = ServiceManager(StdOutputLoggingService)
    dataWeaveScript.exec(inputs, serviceManager)
  }
}

case class WeaveInput(name: String, content: SourceProvider)

sealed trait WeaveExecutionResult {

  def result(): String

  def success(): Boolean

}

case class WeaveSuccessResult(outputStream: OutputStream, charset: String) extends WeaveExecutionResult {
  override def success(): Boolean = true

  override def result(): String = {
    outputStream match {
      case ap: AutoPersistedOutputStream => {
        new String(BinaryValue.getBytesFromSeekableStream(ap.toInputStream, true), charset)
      }
    }
  }
}

case class WeaveFailureResult(message: String) extends WeaveExecutionResult {
  override def success(): Boolean = false

  override def result(): String = message
}


class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}


case class WeaveRunnerConfig(path: Array[String], debug: Boolean, scriptToRun: Option[String], main: Option[String], inputs: Map[String, SourceProvider], outputPath: Option[String])
