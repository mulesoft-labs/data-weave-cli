package org.mule.weave.dwnative.cli

import org.mule.weave.v2.utils.StringHelper.toStringTransformer
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import scala.io.Source

class DataWeaveCLITest extends FreeSpec with Matchers {

  "should work with output application/json" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("output application/json --- (1 to 3)[0]"), new TestConsole(System.in, stream), TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "1"
  }

  "should take into account the env variable for default output" in {
    val console = new TestConsole(System.in, System.out)
    new DataWeaveCLIRunner().run(Array("--list-spells"), console, TestEnvironmentVariableProvider())

    console.fatalMessages.isEmpty shouldBe true
  }

  "should work when listing all the spells" in {
    val stream = new ByteArrayOutputStream()
    val envVarProvider = TestEnvironmentVariableProvider(Map(EnvironmentVariableProvider.DW_DEFAULT_OUTPUT_MIMETYPE_VAR -> "application/xml"))
    new DataWeaveCLIRunner().run(Array("root: 'Mariano'"), new TestConsole(System.in, stream), envVarProvider)
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    val expected =
      """<?xml version='1.0' encoding='UTF-8'?>
        |<root>Mariano</root>""".stripMarginAndNormalizeEOL
    result.trim shouldBe expected
  }

  "should be able to run a local spell" in {
    val stream = new ByteArrayOutputStream()
    val localSpell: File = TestUtils.getMyLocalSpell
    val exitCode = new DataWeaveCLIRunner().run(Array("--local-spell", localSpell.getAbsolutePath), new TestConsole(System.in, stream), TestEnvironmentVariableProvider())
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "\"DW Rules\""
  }

  "should be able to run a local spell with a library" in {
    val stream = new ByteArrayOutputStream()
    val localSpell: File = TestUtils.getMyLocalSpellWithLib
    val exitCode = new DataWeaveCLIRunner().run(Array("--local-spell", localSpell.getAbsolutePath), new TestConsole(System.in, stream), TestEnvironmentVariableProvider())
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "\"DW Rules\""
  }

  "should work with simple script and not output" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("(1 to 3)[0]"), new TestConsole(System.in, stream), TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString
    result.trim shouldBe "1"
  }

  "should work ok when sending payload from stdin" in {
    val input: String =
      """[
        |  1,
        |  2,
        |  3
        |]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("payload[0]"), new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream), TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1"
  }

  "should use var" in {
    val input: String =
      """[
        |  1,
        |  2,
        |  3
        |]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("payload[0]"), new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream), TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1"
  }

  "should work with light formats" in {
    val input: String =
      """[{
        |  "a" : 1,
        |  "b" : 2,
        |  "c" : 3
        |}]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    val testConsole = new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream)
    new DataWeaveCLIRunner().run(Array("input payload json output csv header=false ---payload"), testConsole, TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1,2,3"
  }

  "should work running a script with requires privileges" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val exitCode = new DataWeaveCLIRunner().run(Array(script), testConsole)
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected = """
               |{
               |  "isEmpty": false
               |}""".stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }

  "should running a script with the requires privileges" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val exitCode = new DataWeaveCLIRunner().run(Array("--privileges", "fs::Read,Properties", script), testConsole, TestEnvironmentVariableProvider())
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected = """
                     |{
                     |  "isEmpty": false
                     |}""".stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }
  
  "should fail running a script with requires privileges in untrusted mode" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val exitCode = new DataWeaveCLIRunner().run(Array("--untrusted-code", script), testConsole, TestEnvironmentVariableProvider())
    exitCode shouldBe -1
    val maybeError = testConsole.errorMessages.find(msg => msg.contains("The given required privilege: `Properties` was not being granted for this execution."))
    maybeError.isEmpty shouldBe false
  }

  "should run help command successfully" in {
    val stream = new ByteArrayOutputStream()
    val testConsole = new TestConsole(System.in, stream)
    val exitCode = new DataWeaveCLIRunner().run(Array("--help"), testConsole, TestEnvironmentVariableProvider())
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result should include("usage: dw")
  }

  "should run using parameter" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array(
      "-p", "name", "Mariano",
      "-p", "lastname", "Lischetti",
      "{fullName: params.name ++ \" \" ++  params.lastname}"), 
      new TestConsole(System.in, stream),
      TestEnvironmentVariableProvider())
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected = """
                     |{
                     |  "fullName": "Mariano Lischetti"
                     |}
                     """.stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }
}
