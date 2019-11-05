package org.mule.weave.dwnative

import org.mule.weave.v2.interpreted.RuntimeModuleNodeCompiler
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.ModuleLoader
import org.mule.weave.v2.parser.phase.ModuleLoaderManager
import org.mule.weave.v2.parser.phase.ModuleParsingPhasesManager
import org.mule.weave.v2.parser.phase.ParsingContext
import org.mule.weave.v2.sdk.SPIBasedModuleLoaderProvider

object NativeSystemModuleComponents {
  /**
    * Handles the parsing of the modules that are on the SystemClassLoader
    */
  val systemModuleParser: ModuleParsingPhasesManager = {
    val moduleLoaderManager = ModuleLoaderManager(Seq(ModuleLoader(NativeResourceResolver)), SPIBasedModuleLoaderProvider)
    ModuleParsingPhasesManager(moduleLoaderManager)
  }



  def start(): Unit = {
    systemModuleParser.typeCheckModule(NameIdentifier.CORE_MODULE, ParsingContext(NameIdentifier.anonymous, systemModuleParser))
  }
}
