package org.mule.weave.dwnative.cli

trait EnvironmentVariableProvider {
  def envVar(name: String): Option[String]
}

object EnvironmentVariableProvider {
  val DW_DEFAULT_INPUT_MIMETYPE_VAR: String = "DW_DEFAULT_INPUT_MIMETYPE"
  val DW_DEFAULT_OUTPUT_MIMETYPE_VAR: String = "DW_DEFAULT_OUTPUT_MIMETYPE"
  val DW_HOME_VAR: String = "DW_HOME"
  val DW_WORKING_DIRECTORY_VAR: String = "DW_WORKING_PATH"
  val DW_LIB_PATH_VAR: String = "DW_LIB_PATH"
  val DW_USE_COLOR_VAR: String = "DW_USE_COLOR"
}

object DefaultEnvironmentVariableProvider extends EnvironmentVariableProvider {
  override def envVar(name: String): Option[String] = Option(System.getenv(name))
}