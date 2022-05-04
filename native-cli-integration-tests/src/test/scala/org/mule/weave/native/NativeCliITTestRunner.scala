package org.mule.weave.native

import java.io.File
import java.util.concurrent.TimeUnit
import scala.io.Source

class NativeCliITTestRunner(args: Array[String]) extends ResourceResolver {

  private val NATIVE_CLI_INTEGRATION_TESTS = "native-cli-integration-tests"
  
  private lazy val OS = System.getProperty("os.name").toLowerCase
  
  private lazy val EXECUTABLE_NAME = {
    if (OS.contains("win")) {
      "dw.exe"
    } else {
      "dw"
    }
  }

  private lazy val DW_CLI_EXECUTABLE: String = {
    val path = getResourcePath(getClass.getName.replaceAll("\\.", File.separator) + ".class")
    var nativeCliIntegrationTest = new File(path)
    while (nativeCliIntegrationTest.getName != NATIVE_CLI_INTEGRATION_TESTS) {
      nativeCliIntegrationTest = nativeCliIntegrationTest.getParentFile
    }
    val dwPath = new File(nativeCliIntegrationTest.getParentFile, s"native-cli/build/native/nativeCompile/$EXECUTABLE_NAME")
    dwPath.getAbsolutePath
  }
  
  def execute(): (Int, String) = {
    execute(5, TimeUnit.SECONDS)
  }

  def execute(timeout: Long, unit: TimeUnit): (Int, String) = {
    val command = DW_CLI_EXECUTABLE +: args
    println(s"Executing command: ${command.mkString(" ")}")
    val proc = Runtime.getRuntime.exec(command)
    proc.waitFor(timeout, unit)
    proc.exitValue()
    val source = Source.fromInputStream(proc.getInputStream)
    var out = ""
    try {
      out = source.mkString.trim
    } finally {
      source.close()
    }
    (proc.exitValue(), out)
  }
}

object NativeCliITTestRunner {

  def apply(arg: String): NativeCliITTestRunner = NativeCliITTestRunner(Array(arg))
  
  def apply(args: Array[String]): NativeCliITTestRunner = new NativeCliITTestRunner(args)
}
