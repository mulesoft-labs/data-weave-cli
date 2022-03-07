package org.mule.weave.dwnative.cli.exceptions

class ResourceNotFoundException(name: String) extends CLIException {
  override def getMessage: String = s"Resource: `${name}` was not found in the classpath. Please verify the name and the specified classpath."
}
