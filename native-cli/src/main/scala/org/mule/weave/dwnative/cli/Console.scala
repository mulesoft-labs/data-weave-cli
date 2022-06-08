package org.mule.weave.dwnative.cli

import org.jline.terminal.{Terminal, TerminalBuilder}
import org.mule.weave.dwnative.WeaveFailureResult
import org.mule.weave.dwnative.WeaveSuccessResult
import org.mule.weave.dwnative.cli.highlighting.NanoHighlighterProvider
import org.mule.weave.dwnative.utils.AnsiColor

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.{InputStream, PrintWriter}
import scala.util.Try

/**
  * Wraps the interaction with the console
  */
trait Console {

  private var debugEnabled = false

  /**
    * Returns true if the debug is enabled
    *
    * @return
    */
  def isDebugEnabled(): Boolean = debugEnabled

  def in: InputStream

  def out: OutputStream

  def writer: PrintWriter

  def enableDebug(): Console = {
    debugEnabled = true
    this
  }

  def debug(message: String): Unit

  def info(message: String): Unit

  def error(message: String): Unit

  def fatal(message: String): Unit

  def warn(message: String): Unit

  def clear(): Unit

  def enabledStreaming: Boolean = true
  
  def printResult(failure: WeaveFailureResult): Unit = {
    error("Error while executing the script:")
    error(failure.result())
  }
  
  def printResult(success: WeaveSuccessResult): Unit
}

object DefaultConsole extends Console {

  override def info(message: String): Unit = {
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

  override def warn(message: String): Unit = {
    println(AnsiColor.yellow(message))
  }

  override def debug(message: String): Unit = {
    if (isDebugEnabled())
      println(AnsiColor.green(message))
  }

  override def writer: PrintWriter = new PrintWriter(System.out)

  override def printResult(result: WeaveSuccessResult): Unit = {
    println(result.result())
  }
}

object ColoredConsole extends Console {

  lazy val terminal: Terminal = TerminalBuilder.builder()
    .system(true)
    .jansi(true)
    .build();

  val highLighterProvider = new NanoHighlighterProvider()

  override def info(message: String): Unit = {
    terminal.writer().println(message)
    terminal.writer().flush()
  }

  override def error(message: String): Unit = {
    terminal.writer().println(AnsiColor.red("[ERROR] " + message))
    terminal.writer().flush()
  }

  override def fatal(message: String): Unit = {
    terminal.writer().println(AnsiColor.red("[FATAL] " + message))
    terminal.writer().flush()
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

  override def in: InputStream = terminal.input()

  override def out: OutputStream = if (terminal.getType.equals(Terminal.TYPE_DUMB)) terminal.output() else new ByteArrayOutputStream()

  override def warn(message: String): Unit = {
    terminal.writer().println(AnsiColor.yellow(message))
    terminal.writer().flush()
  }

  override def debug(message: String): Unit = {
    if (isDebugEnabled()) {
      terminal.writer().println(AnsiColor.green(message))
      terminal.writer().flush()
    }
  }

  override def writer: PrintWriter = terminal.writer()

  override def enabledStreaming: Boolean = false

  override def printResult(result: WeaveSuccessResult): Unit = {
    val message = highLighterProvider.hightlighterFor(result.extension.getOrElse("json")).highlight(result.result()).toAnsi(terminal)
    info(message)
  }
}

object ConsoleProvider {
  def provide(color: Boolean): Console = if (color) ColoredConsole else DefaultConsole
}