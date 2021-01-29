package org.mule.weave.native

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.File
import java.util.concurrent.TimeUnit
import scala.io.Source

class NativeCliTest extends FreeSpec with Matchers with BeforeAndAfterAll {


  override protected def beforeAll(): Unit = {
    getDefaultDWHome().mkdirs()
  }

  def getDefaultDWHome(): File = {
    val homeUser = getUserHome()
    val defaultDWHomeDir = new File(homeUser, ".dw")
    defaultDWHomeDir
  }

  def getUserHome(): File = {
    new File(System.getProperty("user.home"))
  }


  private val OS = System.getProperty("os.name").toLowerCase

  "it should execute simple case correctly" in {
    val process = Runtime.getRuntime.exec(Array(getExecutable, "1 to 10"))
    process.waitFor(5, TimeUnit.SECONDS)
    val source = Source.fromInputStream(process.getInputStream)
    try {
      val out = source.mkString.trim
      assert(out == "[\n  1,\n  2,\n  3,\n  4,\n  5,\n  6,\n  7,\n  8,\n  9,\n  10\n]")
    } finally {
      source.close()
    }
  }

  def getPath(resource: String): String = {
    getClass.getClassLoader.getResource(resource).getPath
  }

  "it should execute with input" in {
    val path = getPath("inputs/payload.json")
    val process = Runtime.getRuntime.exec(Array(getExecutable, "-i", "payload", path, "payload.name"))
    process.waitFor(5, TimeUnit.SECONDS)
    val source = Source.fromInputStream(process.getInputStream)
    try {
      val out = source.mkString.trim
      assert(out == "\"Tomo\"")
    } finally {
      source.close()
    }
  }

  "it should execute with input and script" in {
    val process = Runtime.getRuntime.exec(Array(getExecutable, "-i", "payload", getPath("inputs/payload.json"), "-f", getPath("scripts/GetName.dwl")))
    process.waitFor(5, TimeUnit.SECONDS)
    val source = Source.fromInputStream(process.getInputStream)
    try {
      val out = source.mkString.trim
      assert(out == "\"Tomo\"")
    } finally {
      source.close()
    }
  }


  def getExecutableName = {
    if (OS.contains("win")) {
      "dw.exe"
    } else {
      "dw"
    }
  }

  def getExecutable: String = {
    val path = getPath(getClass.getName.replaceAll("\\.", File.separator) + ".class")
    var nativeCliIntegrationTest = new File(path)
    while (nativeCliIntegrationTest.getName != "native-cli-integration-tests") {
      nativeCliIntegrationTest = nativeCliIntegrationTest.getParentFile
    }
    val dwPath = new File(nativeCliIntegrationTest.getParentFile, s"native-cli/build/graal/${getExecutableName}")
    dwPath.getAbsolutePath
  }

}
