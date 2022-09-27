package org.mule.weave.dwnative.cli

import java.io.InputStream
import java.io.OutputStream
import scala.collection.mutable.ArrayBuffer

class TestConsole(val in: InputStream = System.in, val out: OutputStream = System.out, val envVars: Map[String, String] = Map()) extends Console {

  val infoMessages = new ArrayBuffer[String]()
  val errorMessages = new ArrayBuffer[String]()
  val fatalMessages = new ArrayBuffer[String]()
  val warnMessages = new ArrayBuffer[String]()
  var clearCount: Int = 0


  override def doInfo(message: String): Unit = {
    infoMessages += (message)
  }

  override def error(message: String): Unit = {
    errorMessages += (message)
  }

  override def fatal(message: String): Unit = {
    fatalMessages += (message)
  }

  override def doWarn(message: String): Unit = {
    warnMessages += (message)
  }

  override def clear(): Unit = {
    clearCount = clearCount + 1
  }

  override def envVar(name: String): Option[String] = {
    envVars.get(name).orElse(Option(System.getenv(name)))
  }

  override def doDebug(message: String): Unit = DefaultConsole.debug(message)
}
