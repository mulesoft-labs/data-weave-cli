package org.mule.weave.native

import org.scalatest.BeforeAndAfterAll
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.File

class NativeCliTest extends FreeSpec 
  with Matchers 
  with BeforeAndAfterAll 
  with ResourceResolver {
  
  private lazy val USER_HOME: File = new File(System.getProperty("user.home"))

  private lazy val DEFAULT_DW_CLI_HOME: File = {
    val home = new File(USER_HOME, ".dw")
    home
  }
  
  override protected def beforeAll(): Unit = {
    DEFAULT_DW_CLI_HOME.mkdirs()
  }

  "it should execute simple case correctly" in {
    val (_, output) = NativeCliITTestRunner("1 to 10").execute()
    output shouldBe "[\n  1,\n  2,\n  3,\n  4,\n  5,\n  6,\n  7,\n  8,\n  9,\n  10\n]"
  }

  "it should execute with input" in {
    val path = getResourcePath("inputs/payload.json")
    val (_, output) = NativeCliITTestRunner(Array("-i", "payload", path, "payload.name")).execute()
    output shouldBe "\"Tomo\""
  }

  "it should execute with input and script" in {
    val inputPath = getResourcePath("inputs/payload.json")
    val transformationPath = getResourcePath("scripts/GetName.dwl")
    val (_, output) = NativeCliITTestRunner(Array("-i", "payload", inputPath, "-f", transformationPath)).execute()
    output shouldBe "\"Tomo\""
  }
  
  "it should run temperature" in {
    val transformationPath = getResourcePath("scripts/Temperature.dwl")
    val (_, output) = NativeCliITTestRunner(Array("-f", transformationPath)).execute()
    println(s"temperature result: [$output]")
    output shouldBe "{\n  \"temperature\": \"15 ° C\"\n}"
  }
}
