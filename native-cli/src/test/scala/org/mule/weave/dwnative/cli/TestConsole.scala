package org.mule.weave.dwnative.cli

import java.io.InputStream
import java.io.OutputStream
import scala.collection.mutable.ArrayBuffer

class TestConsole(val in: InputStream, val out: OutputStream, val envVars: Map[String, String] = Map()) extends Console {

  val infoMessages = new ArrayBuffer[String]()
  val errorMessages = new ArrayBuffer[String]()
  val fatalMessages = new ArrayBuffer[String]()
  val warnMessages = new ArrayBuffer[String]()


  override def info(message: String): Unit = {
    infoMessages +=(message)
  }

  override def error(message: String): Unit = {
    errorMessages +=(message)
  }

  override def fatal(message: String): Unit = {
    fatalMessages +=(message)
  }

  override def warn(message: String): Unit = {
    warnMessages +=(message)
  }

  override def clear(): Unit = DefaultConsole.clear()

  override def envVar(name: String): Option[String] = envVars.get(name)

  override def debug(message: String): Unit = DefaultConsole.debug(message)
}
