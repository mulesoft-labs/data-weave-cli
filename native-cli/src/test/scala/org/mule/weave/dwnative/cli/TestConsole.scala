package org.mule.weave.dwnative.cli
import java.io.InputStream
import java.io.OutputStream

class TestConsole(val in: InputStream,val out: OutputStream, val envVars: Map[String,String] = Map()) extends Console {

  override def info(message: String): Unit = DefaultConsole.info(message)

  override def error(message: String): Unit = DefaultConsole.error(message)

  override def warn(message: String): Unit = DefaultConsole.warn(message)

  override def clear(): Unit = DefaultConsole.clear()

  override def envVar(name: String): Option[String] = envVars.get(name)

  override def debug(message: String): Unit = DefaultConsole.debug(message)
}
