package org.mule.weave.dwnative

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter

import org.mule.weave.v2.interpreted.DefaultModuleNodeLoader
import org.mule.weave.v2.interpreted.InterpreterMappingCompilerPhase
import org.mule.weave.v2.interpreted.extension.ParsingContextCreator
import org.mule.weave.v2.interpreted.extension.WeaveBasedDataFormatExtensionLoaderService
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.ServiceManager
import org.mule.weave.v2.model.service.StdOutputLoggingService
import org.mule.weave.v2.model.values.BinaryValue
import org.mule.weave.v2.module.CompositeDataFormatExtensionsLoaderService
import org.mule.weave.v2.module.DataFormatExtensionsLoaderService
import org.mule.weave.v2.module.DefaultDataFormatExtensionsLoaderService
import org.mule.weave.v2.module.json.DefaultJsonDataFormat
import org.mule.weave.v2.module.reader.AutoPersistedOutputStream
import org.mule.weave.v2.module.reader.DefaultAutoPersistedOutputStream
import org.mule.weave.v2.module.reader.Reader
import org.mule.weave.v2.module.reader.SourceProvider
import org.mule.weave.v2.parser.MappingParser
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ast.structure.DocumentNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.exception.LocatableException
import org.mule.weave.v2.parser.phase.CompositeModuleParserManager
import org.mule.weave.v2.parser.phase.ModuleLoader
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.parser.phase.ModuleParserManager
import org.mule.weave.v2.parser.phase.ParsingContext
import org.mule.weave.v2.parser.phase.PhaseResult
import org.mule.weave.v2.parser.phase.TypeCheckingResult
import org.mule.weave.v2.resources.NativeResourceLoader
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver

class NativeRuntime(libDir: File, path: Array[File]) {

  private val systemParserManager = ModuleParserManager(ModuleLoaderManager(ModuleLoader(NativeResourceProvider)))
  private val nodeLoader = new DefaultModuleNodeLoader

  private val pathBasedResourceResolver = PathBasedResourceResolver(path ++ Option(libDir.listFiles()).getOrElse(new Array[File](0)))
  private val pathBasedParserManager = ModuleParserManager(ModuleLoaderManager(ModuleLoader(pathBasedResourceResolver)))

  private val defaultModuleManager = ModuleLoaderManager(ModuleLoader(pathBasedResourceResolver))
  DataWeaveUtils.setupServices(defaultModuleManager)

  def getResourceContent(ni: NameIdentifier): Option[String] = {
    pathBasedResourceResolver.resolve(ni).map(_.content())
  }

  def run(script: String, inputs: Array[WeaveInput]): WeaveExecutionResult = {
    run(script, inputs, new DefaultAutoPersistedOutputStream())
  }

  def run(script: String, inputs: Array[WeaveInput], out: OutputStream): WeaveExecutionResult = {
    var contentResult: WeaveExecutionResult = null
    try {
      val parsingContext = new ParsingContext(NameIdentifier.ANONYMOUS_NAME, new MessageCollector(), new CompositeModuleParserManager(systemParserManager, pathBasedParserManager), errorTrace = 0, attachDocumentation = false)
      inputs.foreach((input) => {
        parsingContext.addImplicitInput(input.name, None)
      })

      //      parsingContext.registerParsingPhaseAnnotationProcessor(DependencyAnnotationProcessor.ANNOTATION_NAME, new DependencyAnnotationProcessor(DataWeaveUtils.getLibPathHome()))

      val typeCheckResult = MappingParser.parse(MappingParser.typeCheckPhase(), WeaveResource("", script), parsingContext)

      if (typeCheckResult.hasErrors()) {
        val messages = typeCheckResult.errorMessages()
        val errorMessage = messages.map((message) => {
          "[Error] " + message._2.message + "\n" + message._1.locationString
        }).mkString("\n")
        contentResult = WeaveFailureResult(errorMessage)
      } else {
        val weaveBasedDataFormatManager = WeaveBasedDataFormatExtensionLoaderService(ParsingContextCreator(pathBasedParserManager), pathBasedResourceResolver, nodeLoader)
        val serviceManager = ServiceManager(
          StdOutputLoggingService,
          Map(
            //Services
            classOf[DataFormatExtensionsLoaderService] -> CompositeDataFormatExtensionsLoaderService(DefaultDataFormatExtensionsLoaderService, weaveBasedDataFormatManager)
          )
        )
        implicit val ctx: EvaluationContext = EvaluationContext(serviceManager)
        val value = typeCheckResult.asInstanceOf[PhaseResult[TypeCheckingResult[DocumentNode]]].getResult()
        val result = new InterpreterMappingCompilerPhase(nodeLoader).call(value, parsingContext)

        val executable = result.getResult().executable

        val readers: Map[String, Reader] = inputs.map((input) => {
          executable.declaredInputs()
            .get(input.name)
            .map((declaredInput) => {
              (input.name, declaredInput.reader(input.content))
            }).getOrElse({
            //Default to JSON
            (input.name, DefaultJsonDataFormat.reader(SourceProvider(input.content)))
          })
        }).toMap
        val writer = executable
          .declaredOutput()
          .map(_.writer(Some(out)))
          .getOrElse(new CustomWeaveDataFormat(defaultModuleManager).writer(Some(out)))
        val tuple = executable.write(writer, readers)
        contentResult = WeaveSuccessResult(out, tuple._2.name())
      }
    }
    catch {
      case le: LocatableException => {
        contentResult = WeaveFailureResult(le.getMessage() + " at:\n" + le.location.locationString)
      }
      case le: Exception => {
        val writer = new StringWriter()
        le.printStackTrace()
        le.printStackTrace(new PrintWriter(writer))
        contentResult = WeaveFailureResult("Internal error : " + le.getClass.getName + " : " + le.getMessage + "\n" + writer.toString)
      }

    }
    contentResult
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


object NativeResourceProvider extends WeaveResourceResolver {

  def resolve(name: NameIdentifier): Option[WeaveResource] = {
    val resourceName = name.name
    Option(NativeResourceLoader.getResource(resourceName)).map((resource) => WeaveResource(resourceName, resource))
  }
}


class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}


case class WeaveRunnerConfig(path: Array[String], debug: Boolean, scriptToRun: Option[String], main: Option[String], inputs: Map[String, SourceProvider], outputPath: Option[String])
