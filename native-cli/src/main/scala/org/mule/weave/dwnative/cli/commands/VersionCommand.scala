package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console
import org.mule.weave.v2.version.ComponentVersion

class VersionCommand(logger: Console) extends WeaveCommand {

  val DW_CLI_VERSION: String = ComponentVersion.nativeVersion
  val DW_RUNTIME_VERSION: String = ComponentVersion.weaveVersion

  def exec(): Int = {
    logger.info(" - DataWeave Command Line : V" + DW_CLI_VERSION)
    logger.info(" - DataWeave Runtime: V" + DW_RUNTIME_VERSION)
    0
  }
}
