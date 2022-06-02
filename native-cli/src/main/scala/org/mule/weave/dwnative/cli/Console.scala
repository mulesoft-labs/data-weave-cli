package org.mule.weave.dwnative.cli

import org.jline.terminal.{Terminal, TerminalBuilder}
import org.mule.weave.dwnative.cli.highlighting.NanoHighlighterProvider
import org.mule.weave.dwnative.utils.AnsiColor

import java.io.{ByteArrayOutputStream, InputStream, OutputStream, PrintWriter}
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

  def terminal: Terminal

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

  def highLight(message: String, extension: String): String

  def clear(): Unit

  def envVar(name: String): Option[String]

}

object ColoredConsole extends Console {

  lazy val terminal = TerminalBuilder.builder()
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

  override def envVar(name: String): Option[String] = Option(System.getenv(name))

  override def debug(message: String): Unit = {
    if (isDebugEnabled()) {
      terminal.writer().println(AnsiColor.green(message))
      terminal.writer().flush()
    }
  }

  override def writer: PrintWriter = terminal.writer()

  override def highLight(message: String, extension: String): String = {
    highLighterProvider.hightlighterFor(extension).highlight(message).toAnsi(terminal)
  }
}
