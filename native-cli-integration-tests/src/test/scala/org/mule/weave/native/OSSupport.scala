package org.mule.weave.native

trait OSSupport {
  lazy val OS: String = System.getProperty("os.name").toLowerCase

  def isWindows: Boolean = OS.contains("win")
}
