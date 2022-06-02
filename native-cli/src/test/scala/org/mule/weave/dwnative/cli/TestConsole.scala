package org.mule.weave.dwnative.cli


import org.jline.terminal.Terminal

import java.io.{InputStream, OutputStream, PrintWriter}
import scala.collection.mutable.ArrayBuffer

class TestConsole(val in: InputStream = System.in, val out: OutputStream = System.out, val envVars: Map[String, String] = Map()) extends Console {

  val infoMessages = new ArrayBuffer[String]()
  val errorMessages = new ArrayBuffer[String]()
  val fatalMessages = new ArrayBuffer[String]()
  val warnMessages = new ArrayBuffer[String]()
  var clearCount: Int = 0


  override def info(message: String): Unit = {
    infoMessages += (message)
  }

  override def error(message: String): Unit = {
    errorMessages += (message)
  }

  override def fatal(message: String): Unit = {
    fatalMessages += (message)
  }

  override def warn(message: String): Unit = {
    warnMessages += (message)
  }

  override def clear(): Unit = {
    clearCount = clearCount + 1
  }

  override def envVar(name: String): Option[String] = envVars.get(name)

  override def debug(message: String): Unit = ColoredConsole.debug(message)

  override def writer: PrintWriter = new PrintWriter(out)

  override def terminal: Terminal = null

  override def highLight(message: String, extension: String): String = message
}
