package org.mule.weave.dwnative

object AnsiColor {

  def red(txt: String): String =
    "\u001b[31m" + txt + "\u001b[0m"

  def green(txt: String): String =
    "\u001B[32m" + txt + "\u001b[0m"

  def yellow(txt: String): String =
    "\u001b[33m" + txt + "\u001b[0m"

}