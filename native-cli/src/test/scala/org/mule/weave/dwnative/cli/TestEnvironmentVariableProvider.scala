package org.mule.weave.dwnative.cli

class TestEnvironmentVariableProvider(val envVars: Map[String, String] = Map()) extends EnvironmentVariableProvider {
  override def envVar(name: String): Option[String] = envVars.get(name)
}

object TestEnvironmentVariableProvider {
  def apply(envVars: Map[String, String] = Map()): TestEnvironmentVariableProvider = new TestEnvironmentVariableProvider(envVars)
}
