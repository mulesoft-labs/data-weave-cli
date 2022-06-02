package org.mule.weave.dwnative

import io.netty.util.internal.PlatformDependent
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.initializer.NativeSystemModuleComponents
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.v2.exception.InvalidLocationException
import org.mule.weave.v2.interpreted.{CustomRuntimeModuleNodeCompiler, RuntimeModuleNodeCompiler}
import org.mule.weave.v2.interpreted.module.WeaveDataFormat
import org.mule.weave.v2.io.service.CustomWorkingDirectoryService
import org.mule.weave.v2.io.service.WorkingDirectoryService
import org.mule.weave.v2.model.EvaluationContext
import org.mule.weave.v2.model.ServiceManager
import org.mule.weave.v2.model.service.CharsetProviderService
import org.mule.weave.v2.model.service.DefaultSecurityManagerService
import org.mule.weave.v2.model.service.LoggingService
import org.mule.weave.v2.model.service.ProtocolUrlSourceProviderResolverService
import org.mule.weave.v2.model.service.ReadFunctionProtocolHandler
import org.mule.weave.v2.model.service.SecurityManagerService
import org.mule.weave.v2.model.service.UrlProtocolHandler
import org.mule.weave.v2.model.service.UrlSourceProviderResolverService
import org.mule.weave.v2.model.service.WeaveRuntimePrivilege
import org.mule.weave.v2.model.values.BinaryValue
import org.mule.weave.v2.module.reader.{AutoPersistedOutputStream, SourceProvider}
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.exception.LocatableException
import org.mule.weave.v2.parser.location.LocationCapable
import org.mule.weave.v2.parser.phase._
import org.mule.weave.v2.runtime._
import org.mule.weave.v2.sdk.{SPIBasedModuleLoaderProvider, TwoLevelWeaveResourceResolver, WeaveResourceResolver}

import java.io.{File, OutputStream, PrintWriter, StringWriter}
import java.nio.charset.{Charset, StandardCharsets}

class NativeRuntime(libDir: File, path: Array[File], console: Console) {

  private val dataWeaveUtils = new DataWeaveUtils(console)

  private val pathBasedResourceResolver: PathBasedResourceResolver = PathBasedResourceResolver(path ++ Option(libDir.listFiles()).getOrElse(new Array[File](0)))

  private val weaveScriptingEngine: DataWeaveScriptingEngine = {
    setupEnv()
    DataWeaveScriptingEngine(new NativeModuleComponentFactory(() => pathBasedResourceResolver, systemFirst = true), ParserConfiguration())
  }

  if (console.isDebugEnabled()) {
    weaveScriptingEngine.enableProfileParsing()
  }

  /**
    * Setup initialization properties
    */
  private def setupEnv(): Unit = {
    System.setProperty("io.netty.processId", Math.abs(PlatformDependent.threadLocalRandom.nextInt).toString)
    System.setProperty("io.netty.noUnsafe", true.toString)
  }

  def getResourceContent(ni: NameIdentifier): Option[String] = {
    pathBasedResourceResolver.resolve(ni).map(_.content())
  }

  def run(script: String, nameIdentifier: String, inputs: ScriptingBindings, out: OutputStream, defaultOutputMimeType: String = "application/json", maybePrivileges: Option[Seq[String]] = None): WeaveExecutionResult = {
    try {
      val dataWeaveScript: DataWeaveScript = compileScript(script, inputs, NameIdentifier(nameIdentifier), defaultOutputMimeType)
      val serviceManager: ServiceManager = createServiceManager(maybePrivileges)
      val result: DataWeaveResult = dataWeaveScript.write(inputs, serviceManager, Option(out))
      var extension = result.getExtension()
      if (extension != null && extension.length > 2) {
        extension = extension.substring(1);
      }
      WeaveSuccessResult(out, result.getCharset().name(), Some(extension))
    } catch {
      case cr: CompilationException =>
        WeaveFailureResult(cr.getMessage())
      case le: LocatableException =>
        WeaveFailureResult(le.getMessage() + " at:\n" + le.location.locationString)
      case se: ScriptingEngineSetupException =>
        WeaveFailureResult(se.getMessage)
      case le: Exception =>
        val writer = new StringWriter()
        le.printStackTrace(new PrintWriter(writer))
        WeaveFailureResult("Internal error : " + le.getClass.getName + " : " + le.getMessage + "\n" + writer.toString)
    }
  }

  private def compileScript(script: String, inputs: ScriptingBindings, nameIdentifier: NameIdentifier, defaultOutputMimeType: String) = {
    weaveScriptingEngine.compile(script, nameIdentifier, inputs.entries().map(wi => new InputType(wi, None)).toArray, defaultOutputMimeType)
  }
  
  private def createServiceManager(maybePrivileges: Option[Seq[String]] = None): ServiceManager = {
    
    val charsetProviderService = new CharsetProviderService {
      override def defaultCharset(): Charset = {
        StandardCharsets.UTF_8
      }
    }
    
    var customServices: Map[Class[_], _] = Map(
      classOf[UrlSourceProviderResolverService] -> new ProtocolUrlSourceProviderResolverService(Seq(UrlProtocolHandler, WeavePathProtocolHandler(pathBasedResourceResolver))),
      classOf[WorkingDirectoryService] -> new CustomWorkingDirectoryService(dataWeaveUtils.getWorkingHome(), true),
      classOf[CharsetProviderService] -> charsetProviderService
    )

    if (maybePrivileges.isDefined) {
      val privileges = maybePrivileges.get
      val weaveRuntimePrivileges = privileges.map(WeaveRuntimePrivilege(_)).toArray
      customServices = customServices + (classOf[SecurityManagerService] -> new DefaultSecurityManagerService(weaveRuntimePrivileges))
    }
    ServiceManager(new ConsoleLogger(console), customServices)
  }

  def eval(script: String, inputs: ScriptingBindings, nameIdentifier: String, maybePrivileges: Option[Seq[String]] = None): ExecuteResult = {
    val dataWeaveScript: DataWeaveScript = compileScript(script, inputs, NameIdentifier(nameIdentifier), "application/dw")
    val serviceManager: ServiceManager = createServiceManager(maybePrivileges)
    dataWeaveScript.exec(inputs, serviceManager)
  }
}

class WeavePathProtocolHandler(path: PathBasedResourceResolver) extends ReadFunctionProtocolHandler {

  private val CLASSPATH_PREFIX = "classpath://"

  override def handles(url: String): Boolean = {
    url.startsWith(CLASSPATH_PREFIX)
  }

  override def createSourceProvider(url: String, locatable: LocationCapable, charset: Charset): SourceProvider = {
    val uri = url.stripPrefix(CLASSPATH_PREFIX)
    val wellFormedUri = if (uri.startsWith("/")) {
      uri.substring(1)
    } else {
      uri
    }
    val maybeResource = path.resolve(wellFormedUri)
    maybeResource match {
      case Some(value) => {
        SourceProvider(value, charset)
      }
      case None => throw new InvalidLocationException(locatable.location(), uri)
    }
  }
}

class ConsoleLogger(console: Console) extends LoggingService {
  override def isInfoEnabled(): Boolean = true

  override def logInfo(msg: String): Unit = console.info(msg)

  override def logError(msg: String): Unit = console.error(msg)

  override def logWarn(msg: String): Unit = console.warn(msg)
}

object WeavePathProtocolHandler {
  def apply(path: PathBasedResourceResolver): WeavePathProtocolHandler = new WeavePathProtocolHandler(path)
}


class NativeModuleComponentFactory(dynamicLevel: () => WeaveResourceResolver, systemFirst: Boolean) extends ModuleComponentsFactory {

  /**
    * Handles the compilation of modules that are on the SystemClassLoader
    */
  val systemModuleCompiler: RuntimeModuleNodeCompiler = RuntimeModuleNodeCompiler(NativeSystemModuleComponents.systemModuleParser)


  override def createComponents(): ModuleComponents = {
    val weaveResourceResolver = dynamicLevel()
    val currentClassloader: ModuleParsingPhasesManager = ModuleParsingPhasesManager(ModuleLoaderManager(Seq(ModuleLoader(weaveResourceResolver)), new SPIBasedModuleLoaderProvider(weaveResourceResolver)))
    val parser: CompositeModuleParsingPhasesManager = CompositeModuleParsingPhasesManager(NativeSystemModuleComponents.systemModuleParser, currentClassloader)
    val compiler: CustomRuntimeModuleNodeCompiler = RuntimeModuleNodeCompiler.chain(currentClassloader, systemModuleCompiler, parentLast = !systemFirst)
    ModuleComponents(new TwoLevelWeaveResourceResolver(NativeSystemModuleComponents.systemResourceResolver, () => weaveResourceResolver), parser, compiler)
  }
}

case class WeaveInput(name: String, content: SourceProvider)

sealed trait WeaveExecutionResult {

  def result(): String

  def extension(): Option[String]

  def success(): Boolean

}

case class WeaveSuccessResult(outputStream: OutputStream, charset: String, extension: Option[String]) extends WeaveExecutionResult {
  override def success(): Boolean = true

  override def result(): String = {
    outputStream match {
      case ap: AutoPersistedOutputStream => {
        implicit val context: EvaluationContext = EvaluationContext()
        try {
          new String(BinaryValue.getBytesFromSeekableStream(ap.toInputStream, close = true, memoryService = context.serviceManager.memoryService), charset)
        } finally {
          context.close()
        }
      }
      case _ => {
        outputStream.toString
      }
    }
  }
}

case class WeaveFailureResult(message: String, extension: Option[String] = None) extends WeaveExecutionResult {
  override def success(): Boolean = false

  override def result(): String = message
}


class CustomWeaveDataFormat(moduleManager: ModuleLoaderManager) extends WeaveDataFormat {
  override def createModuleLoader(): ModuleLoaderManager = moduleManager
}

