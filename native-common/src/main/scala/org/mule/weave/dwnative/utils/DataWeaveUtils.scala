package org.mule.weave.dwnative.utils

import java.io.File

import org.mule.weave.dwnative.CustomWeaveDataFormat
import org.mule.weave.v2.env.StaticServiceProvider
import org.mule.weave.v2.env.WeaveRuntime
import org.mule.weave.v2.model.ServiceRegistration
import org.mule.weave.v2.module.DataFormat
import org.mule.weave.v2.module.csv.CSVDataFormat
import org.mule.weave.v2.module.json.JsonDataFormat
import org.mule.weave.v2.module.multipart.MultiPartDataFormat
import org.mule.weave.v2.module.native.NativeValueProvider
import org.mule.weave.v2.module.octetstream.OctetStreamDataFormat
import org.mule.weave.v2.module.properties.PropertiesDataFormat
import org.mule.weave.v2.module.textplain.TextPlainDataFormat
import org.mule.weave.v2.module.xml.XmlDataFormat
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.runtime.core.SystemNativeValueProvider
import org.mule.weave.v2.runtime.core.functions.ReadFunctionProtocolHandler
import org.mule.weave.v2.runtime.core.functions.UrlProtocolHandler

object DataWeaveUtils {

  def getDWHome(): File = {
    val homeUser = new File(System.getProperty("user.home"))
    val weavehome = System.getenv("DW_HOME")
    if (weavehome != null) {
      val home = new File(weavehome)
      if (!home.exists()) {
        println(AnsiColor.red(s"[error] Weave Home Directory `${weavehome}` declared on environment variable `WEAVE_HOME` does not exists."))
      }
      home
    } else {
      if (WeaveProperties.verbose) {
        println("[debug] Env not working trying home directory")
      }
      val defaultDWHomeDir = new File(homeUser, ".dw")
      if (defaultDWHomeDir.exists()) {
        defaultDWHomeDir
      } else {
        val dwScriptPath = System.getenv("_")
        if(dwScriptPath != null) {
          val scriptPath = new File(dwScriptPath)
          if(scriptPath.isFile && scriptPath.getName == "dw"){
            return scriptPath.getAbsoluteFile.getParentFile
          }
        }
        println(AnsiColor.yellow(s"[warning] Unable to detect Weave Home directory so local directory is going to be used. Please either define the env variable WEAVE_HOME or copy the weave distro into `${defaultDWHomeDir.getAbsolutePath}`."))
        new File("..")
      }
    }
  }

  def getLibPathHome(): File = {
    val weavehome = System.getenv("DW_LIB_PATH")
    if (weavehome != null) {
      val home = new File(weavehome)
      if (!home.exists()) {
        println(AnsiColor.red(s"[error] Weave Library Home Directory `${weavehome}` declared on environment variable `DW_LIB_PATH` does not exists."))
      }
      home
    } else {
      new File(getDWHome(), "libs")
    }
  }

  def setupServices(moduleLoaderManager: ModuleLoaderManager): Unit = {
    val services: Map[Class[_], Seq[_]] = Map(
      classOf[NativeValueProvider] -> Seq(new SystemNativeValueProvider()), //Native Functions
      classOf[DataFormat[_, _]] -> Seq(
        new CSVDataFormat,
        new JsonDataFormat,
        new XmlDataFormat,
        new CustomWeaveDataFormat(moduleLoaderManager),
        new TextPlainDataFormat,
        new OctetStreamDataFormat,
        new PropertiesDataFormat,
        new MultiPartDataFormat),
      classOf[ServiceRegistration[_]] -> Seq(),
      classOf[ReadFunctionProtocolHandler] -> Seq(new UrlProtocolHandler())
    )
    //Configure static provider
    WeaveRuntime.setServiceProvider(new StaticServiceProvider(services))
  }
}

object WeaveProperties {
  var verbose: Boolean = false
}
