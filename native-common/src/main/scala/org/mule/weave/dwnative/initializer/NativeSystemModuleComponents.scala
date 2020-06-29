package org.mule.weave.dwnative.initializer

import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.ModuleLoader
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.parser.phase.ModuleParsingPhasesManager
import org.mule.weave.v2.parser.phase.ParsingContext
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver
import org.mule.weave.v2.sdk.WeaveResourceResolver

object NativeSystemModuleComponents {
  /**
    * The system resource resolver
    */
  val systemResourceResolver: WeaveResourceResolver = ClassLoaderWeaveResourceResolver()
  /**
    * Handles the parsing of the modules that are on the SystemClassLoader
    */
  val systemModuleParser: ModuleParsingPhasesManager =
    ModuleParsingPhasesManager(ModuleLoaderManager(ModuleLoader(systemResourceResolver)))

  def start(): Unit = {
    systemModuleParser.typeCheckModule(NameIdentifier.CORE_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
    systemModuleParser.typeCheckModule(NameIdentifier.ARRAYS_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
    systemModuleParser.typeCheckModule(NameIdentifier.OBJECTS_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
    systemModuleParser.typeCheckModule(NameIdentifier.RUNTIME_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
    systemModuleParser.typeCheckModule(NameIdentifier.SYSTEM_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
//    systemModuleParser.typeCheckModule(NameIdentifier("dw::io::http::Server"), ParsingContext(NameIdentifier.anonymous, systemModuleParser))
//    systemModuleParser.typeCheckModule(NameIdentifier("dw::io::file::FileSystem"), ParsingContext(NameIdentifier.anonymous, systemModuleParser))
//    systemModuleParser.typeCheckModule(NameIdentifier("dw::deps::Deps"), ParsingContext(NameIdentifier.anonymous, systemModuleParser))
  }
}
