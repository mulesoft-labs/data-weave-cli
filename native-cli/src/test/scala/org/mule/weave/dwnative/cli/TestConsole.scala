package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.WeaveSuccessResult

import java.io.{InputStream, OutputStream, PrintWriter}
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ArrayBuffer

class TestConsole(val in: InputStream = System.in, val out: OutputStream = System.out) extends Console {

  val infoMessages = new ArrayBuffer[String]()
  val errorMessages = new ArrayBuffer[String]()
  val fatalMessages = new ArrayBuffer[String]()
  val warnMessages = new ArrayBuffer[String]()
  var clearCount: Int = 0
  
  override def info(message: String): Unit = {
    infoMessages += message
  }

  override def error(message: String): Unit = {
    errorMessages += message
  }

  override def fatal(message: String): Unit = {
    fatalMessages += message
  }

  override def warn(message: String): Unit = {
    warnMessages += message
  }

  override def clear(): Unit = {
    clearCount = clearCount + 1
  }

  override def debug(message: String): Unit = ColoredConsole.debug(message)

  override def writer: PrintWriter = new PrintWriter(out)

  override def printResult(result: WeaveSuccessResult): Unit = {
    out.write(result.result().getBytes(StandardCharsets.UTF_8))
  }
}
