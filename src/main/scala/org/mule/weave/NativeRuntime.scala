package org.mule.weave


import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.zip.ZipFile

import org.mule.weave.v2.env.StaticServiceProvider
import org.mule.weave.v2.env.WeaveRuntime
import org.mule.weave.v2.interpreted.DefaultModuleNodeLoader
import org.mule.weave.v2.interpreted.InterpreterMappingCompilerPhase
import org.mule.weave.v2.interpreted.extension.MultiWeaveResourceResolver
import org.mule.weave.v2.interpreted.extension.ParsingContextCreator
import org.mule.weave.v2.interpreted.extension.WeaveBasedDataFormatExtensionLoaderService
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.interpreted.module.WeaveWriter
import org.mule.weave.v2.interpreted.module.WeaveWriterSettings
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.ServiceManager
import org.mule.weave.v2.model.ServiceRegistration
import org.mule.weave.v2.model.service.StdOutputLoggingService
import org.mule.weave.v2.module.CompositeDataFormatExtensionsLoaderService
import org.mule.weave.v2.module.DataFormat
import org.mule.weave.v2.module.DataFormatExtensionsLoaderService
import org.mule.weave.v2.module.DataFormatManager
import org.mule.weave.v2.module.DefaultDataFormatExtensionsLoaderService
import org.mule.weave.v2.module.csv.CSVDataFormat
import org.mule.weave.v2.module.json.DefaultJsonDataFormat
import org.mule.weave.v2.module.json.JsonDataFormat
import org.mule.weave.v2.module.multipart.MultiPartDataFormat
import org.mule.weave.v2.module.native.NativeValueProvider
import org.mule.weave.v2.module.octetstream.OctetStreamDataFormat
import org.mule.weave.v2.module.octetstream.OctetStreamReader
import org.mule.weave.v2.module.properties.PropertiesDataFormat
import org.mule.weave.v2.module.reader.Reader
import org.mule.weave.v2.module.reader.SourceProvider
import org.mule.weave.v2.module.textplain.TextPlainDataFormat
import org.mule.weave.v2.module.xml.XmlDataFormat
import org.mule.weave.v2.parser.MappingParser
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ast.structure.DocumentNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.exception.LocatableException
import org.mule.weave.v2.parser.phase.ModuleLoader
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.parser.phase.ModuleParserManager
import org.mule.weave.v2.parser.phase.ParsingContext
import org.mule.weave.v2.parser.phase.PhaseResult
import org.mule.weave.v2.parser.phase.TypeCheckingResult
import org.mule.weave.v2.resources.JSResourceLoader
import org.mule.weave.v2.runtime.core.SystemNativeValueProvider
import org.mule.weave.v2.runtime.core.functions.ReadFunctionProtocolHandler
import org.mule.weave.v2.runtime.core.functions.UrlProtocolHandler
import org.mule.weave.v2.sdk.ChainedWeaveResourceResolver
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver
import org.mule.weave.v2.utils.WeaveConstants
import org.mule.weave.v2.utils.WeaveFile

import scala.collection.mutable
import scala.io.Source


class NativeRuntime {


  def getDWHome(): File = {
    //TODO we need a way to determine the script path directory
    val homeUser = new File(System.getProperty("user.home"))
    val weavehome = System.getenv("WEAVE_HOME")
    if (weavehome != null) {
      new File(weavehome)
    } else {
      val defaultDWHomeDir = new File(homeUser, ".dw")
      if (defaultDWHomeDir.exists()) {
        defaultDWHomeDir
      } else {
        new File("..")
      }
    }
  }

  def run(script: String, inputs: Array[WeaveInput]): WeaveResult = {

    val pathBasedResourceResolver = {
      val file = new File(getDWHome(), "path")
      val path = if (file.exists()) {
        file.listFiles().map(_.getAbsolutePath)
      } else {
        Array[String]()
      }
      PathBasedResourceResolver(path)
    }
    val resolver = new ChainedWeaveResourceResolver(Seq(NativeResourceProvider, pathBasedResourceResolver))
    val moduleLoaderManager = ModuleLoaderManager(ModuleLoader(NativeResourceProvider))
    val parserManager = ModuleParserManager(moduleLoaderManager)

    val weaveDataFormat = new CustomWeaveDataFormat(moduleLoaderManager)
    val services: Map[Class[_], Seq[_]] = Map(
      classOf[NativeValueProvider] -> Seq(new SystemNativeValueProvider()), //Native Functions
      classOf[DataFormat[_, _]] -> Seq(
        new CSVDataFormat,
        new JsonDataFormat,
        new XmlDataFormat,
        weaveDataFormat,
        new TextPlainDataFormat,
        new OctetStreamDataFormat,
        new PropertiesDataFormat,
        new MultiPartDataFormat),
      classOf[ServiceRegistration[_]] -> Seq(),
      classOf[ReadFunctionProtocolHandler] -> Seq(new UrlProtocolHandler()))
    //Configure static provider
    WeaveRuntime.setServiceProvider(new StaticServiceProvider(services))

    val nodeLoader = new DefaultModuleNodeLoader

    val parsingContext = new ParsingContext(NameIdentifier.ANONYMOUS_NAME, new MessageCollector(), parserManager, errorTrace = 0, attachDocumentation = false)
    inputs.foreach((key) => {
      parsingContext.addImplicitInput(key.name, None)
    })
    val typeCheckResult = MappingParser.parse(MappingParser.typeCheckPhase(), WeaveResource("", script), parsingContext)
    val weaveBasedDataFormatManager = WeaveBasedDataFormatExtensionLoaderService(ParsingContextCreator(parserManager), pathBasedResourceResolver, nodeLoader)
    val serviceManager = ServiceManager(
      StdOutputLoggingService,
      Map(
        //Services
        classOf[DataFormatExtensionsLoaderService] -> CompositeDataFormatExtensionsLoaderService(DefaultDataFormatExtensionsLoaderService, weaveBasedDataFormatManager)))
    implicit val ctx: EvaluationContext = EvaluationContext(serviceManager)
    val value = typeCheckResult.asInstanceOf[PhaseResult[TypeCheckingResult[DocumentNode]]].getResult()
    val result = new InterpreterMappingCompilerPhase(nodeLoader).call(value, parsingContext)

    val executable = result.getResult().executable
    val out = new ByteArrayOutputStream()
    val readers: Map[String, Reader] = inputs.map((input) => {
      executable.declaredInputs().get(input.name)
        .map((declaredInput) => {
          (input.name, declaredInput.reader(SourceProvider(input.content)))
        }).getOrElse({
        //Default to JSON
        (input.name, DefaultJsonDataFormat.reader(SourceProvider(input.content)))
      })
    }).toMap
    val writer = executable.declaredOutput().map(_.writer(Some(out))).getOrElse(WeaveWriter(out, new WeaveWriterSettings()))
    var contentResult: String = ""
    var success: Boolean = false
    try {
      executable.write(writer, readers)
      contentResult = out.toString
      success = true
    } catch {
      case le: LocatableException => {
        contentResult = le.getMessage() + " at:\n" + le.location.locationString
      }
      case le: Exception => {
        val writer = new StringWriter()
        le.printStackTrace()
        le.printStackTrace(new PrintWriter(writer))
        contentResult = "Internal error : " + le.getClass.getName + " : " + le.getMessage + "\n" + writer.toString
      }
    }
    WeaveResult(contentResult, success)

  }
}

case class WeaveInput(name: String, content: String)

case class WeaveResult(result: String, success: Boolean)


object NativeResourceProvider extends WeaveResourceResolver {

  def resolve(name: NameIdentifier): Option[WeaveResource] = {
    val resourceName = name.name
    Option(JSResourceLoader.getResource(resourceName)).map((resource) => WeaveResource(resourceName, resource))
  }
}


class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}

class PathBasedResourceResolver(pathEntries: Array[_ <: String]) extends WeaveResourceResolver with MultiWeaveResourceResolver {

  def loadResources(): Map[NameIdentifier, Seq[WeaveResource]] = {

    val start = System.currentTimeMillis()
    val result = mutable.ArrayBuffer[(NameIdentifier, WeaveResource)]()
    pathEntries
      .foreach(f = (entry) => {
        val pathEntryFile = new File(entry)
        if (pathEntryFile.isFile) {
          val zipFile = new ZipFile(pathEntryFile)
          val entries = zipFile.entries
          while (entries.hasMoreElements) {
            val entry = entries.nextElement
            val path = entry.getName
            if (!entry.isDirectory && path.endsWith(WeaveFile.fileExtension)) {
              val identifier = NameIdentifier.fromPath(path)
              val stream = zipFile.getInputStream(entry)
              val source = Source.fromInputStream(stream, WeaveConstants.default_encoding)
              try {
                result.+=((identifier, WeaveResource(path, source.mkString)))
              } finally {
                source.close()
              }
            }
          }
        } else if (pathEntryFile.isDirectory) {
          //Load file from directory
          val rootPath = pathEntryFile.getAbsolutePath
          recursiveListFiles(
            pathEntryFile,
            (f) => {
              val relativeName = f.getAbsolutePath.substring(rootPath.length + 1)
              val source = Source.fromFile(f, WeaveConstants.default_encoding)
              try {
                val resource = WeaveResource.apply(f.getAbsolutePath, source.mkString)
                result.+=((NameIdentifier.fromPath(relativeName), resource))
              } finally {
                source.close()
              }
            })
        }
      })
    val identifierToResources = result.groupBy(_._1).mapValues(_.map(_._2))

    identifierToResources
  }

  def recursiveListFiles[T](f: File, callback: (File) => Unit): Unit = {
    val files = f.listFiles
    files.foreach((f) =>
      if (f.isFile && f.getName.endsWith(WeaveFile.fileExtension)) {
        callback(f)
      } else if (f.isDirectory) {
        recursiveListFiles(f, callback)
      })

  }

  lazy val entries: Map[NameIdentifier, Seq[WeaveResource]] = loadResources()

  override def resolve(name: NameIdentifier): Option[WeaveResource] = {
    entries.get(name).flatMap(_.headOption)
  }

  override def resolveAll(name: NameIdentifier): Seq[WeaveResource] = {
    entries.getOrElse(name, Seq())
  }
}

object PathBasedResourceResolver {
  def apply(pathEntries: Array[_ <: String]): PathBasedResourceResolver = new PathBasedResourceResolver(pathEntries)
}

case class WeaveRunnerConfig(path: Array[String], debug: Boolean, scriptToRun: Option[String], main: Option[String], inputs: Map[String, SourceProvider], outputPath: Option[String])
