package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.utils.AnsiColor

import java.io.InputStream
import java.io.OutputStream
import scala.util.Try

/**
  * Wraps the interaction with the console
  */
trait Console {

  private var silentEnabled = false
  private var debugEnabled = false

  def enableSilent(): Console = {
    silentEnabled = true
    this
  }

  def isSilentEnabled(): Boolean = silentEnabled

  /**
    * Returns true if the debug is enabled
    *
    * @return
    */
  def isDebugEnabled(): Boolean = debugEnabled

  def in: InputStream

  def out: OutputStream

  def enableDebug(): Console = {
    debugEnabled = true
    this
  }

  def debug(message: String): Unit = {
    if (!isSilentEnabled() && isDebugEnabled()) {
      doDebug(message)
    }
  }

  def info(message: String): Unit = {
    if (!isSilentEnabled()) {
      doInfo(message)
    }
  }

  def warn(message: String): Unit = {
    if (!isSilentEnabled()) {
      doWarn(message)
    }
  }

  protected def doDebug(message: String): Unit

  protected def doInfo(message: String): Unit

  protected def doWarn(message: String): Unit

  def error(message: String): Unit

  def fatal(message: String): Unit

  def clear(): Unit

  def envVar(name: String): Option[String]

}

object DefaultConsole extends Console {
  override def doInfo(message: String): Unit = {
    println(message)
  }

  override def error(message: String): Unit = {
    System.err.println(AnsiColor.red("[ERROR] " + message))
  }

  override def fatal(message: String): Unit = {
    System.err.println(AnsiColor.red("[FATAL] " + message))
  }

  override def clear(): Unit = {
    Try({
      if (System.getProperty("os.name").contains("Windows")) {
        new ProcessBuilder("cmd", "/c", "cls").inheritIO.start.waitFor
      }
      else {
        System.out.print("\u001b\u0063")
      }
    })
  }

  override def in: InputStream = System.in

  override def out: OutputStream = System.out

  override def doWarn(message: String): Unit = {
    println(AnsiColor.yellow(message))
  }

  override def envVar(name: String): Option[String] = Option(System.getenv(name))

  override def doDebug(message: String): Unit = {

    println(AnsiColor.green(message))
  }
}
