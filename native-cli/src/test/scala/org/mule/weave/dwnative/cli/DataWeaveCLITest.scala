package org.mule.weave.dwnative.cli

import org.mule.weave.cli.DWCLI.DWFactory
import org.mule.weave.cli.DWCLI.DataWeaveCLIRunner
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.v2.utils.StringHelper.toStringTransformer
import org.scalatest.FreeSpec
import org.scalatest.Matchers
import picocli.CommandLine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import scala.io.Source

class DataWeaveCLITest extends FreeSpec with Matchers {

  "should work with output application/json" in {
    val stream = new ByteArrayOutputStream()
    val console = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(console)
    dwcli.execute("run", "output application/json --- (1 to 3)[0]")
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "1"
  }

  "should support literal inputs" in {
    val stream = new ByteArrayOutputStream()
    val console = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(console)
    dwcli.execute("run", "--literal-input", "test=[1,2,3]",
      "input test json\n" +
        " output json \n" +
        "---\n" +
        "test[1]")
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "2"
  }

  private def createCommandLine(console: TestConsole) = {
    new CommandLine(new DataWeaveCLIRunner(), new DWFactory(console))
  }

  "should take into account the env variable for default output" in {
    val console = new TestConsole(System.in, System.out, Map())
    val dwcli = createCommandLine(console)
    dwcli.execute("list-spells")
    console.fatalMessages.isEmpty shouldBe true
  }

  "should work when listing all the spells" in {
    val stream = new ByteArrayOutputStream()
    val dwcli = createCommandLine(new TestConsole(System.in, stream, Map(DataWeaveUtils.DW_DEFAULT_OUTPUT_MIMETYPE_VAR -> "application/xml")))
    dwcli.execute("run", "root: 'Mariano'")
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
    val dwcli = createCommandLine(new TestConsole(System.in, stream))
    val exitCode = dwcli.execute("spell", "--local", localSpell.getName, "--spell-home", localSpell.getParentFile.getAbsolutePath)
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "\"DW Rules\""
  }

  "should be able to run a local spell with a library" in {
    val stream = new ByteArrayOutputStream()
    val localSpell: File = TestUtils.getMyLocalSpellWithLib
    val dwcli = createCommandLine(new TestConsole(System.in, stream))
    val exitCode = dwcli.execute("spell", "--local", localSpell.getName, "--spell-home", localSpell.getParentFile.getAbsolutePath)
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "\"DW Rules\""
  }

  "should be able to run a local spell with a dependency" in {
    val stream = new ByteArrayOutputStream()
    val localSpell: File = TestUtils.getSimpleSpellWithDependencies
    val console = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(console)
    val exitCode = dwcli.execute("spell", "--local", localSpell.getName, "--spell-home", localSpell.getParentFile.getAbsolutePath)
    console.infoMessages.foreach((m) => {
      println(s"[INFO] ${m}")
    })
    console.errorMessages.foreach((m) => {
      println(s"[ERROR] ${m}")
    })

    console.fatalMessages.foreach((m) => {
      println(s"[FATAL] ${m}")
    })
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "3"
  }

  "should work with simple script and not output" in {
    val stream = new ByteArrayOutputStream()
    val dwcli = createCommandLine(new TestConsole(System.in, stream))
    val exitCode = dwcli.execute("run", "(1 to 3)[0]")
    exitCode shouldBe 0
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
    val dwcli = createCommandLine(new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream))
    val exitCode = dwcli.execute("run", "payload[0]")
    exitCode shouldBe 0
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
    val dwcli = createCommandLine(new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream))
    val exitCode = dwcli.execute("run", "payload[0]")
    exitCode shouldBe 0
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
    val dwcli = createCommandLine(testConsole)
    val exitCode = dwcli.execute("run", "input payload json output csv header=false ---payload")
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1,2,3"
  }

  "should work running a script with requires privileges" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(testConsole)
    val exitCode = dwcli.execute("run", script)
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected =
      """
        |{
        |  "isEmpty": false
        |}""".stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }

  "should running a script with the requires privileges" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(testConsole)
    val exitCode = dwcli.execute("run", "--privileges=fs::Read,Properties", script)
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected =
      """
        |{
        |  "isEmpty": false
        |}""".stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }

  "should fail running a script with requires privileges in untrusted mode" in {
    val stream = new ByteArrayOutputStream()
    val script = """import props from dw::Runtime output application/json --- {isEmpty: isEmpty(props())}""".stripMargin
    val testConsole = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(testConsole)
    val exitCode = dwcli.execute("run", "--untrusted", script)
    exitCode shouldBe -1
    val maybeError = testConsole.errorMessages.find(msg => msg.contains("The given required privilege: `Properties` was not being granted for this execution."))
    maybeError.isEmpty shouldBe false
  }

  "should run using parameter" in {
    val stream = new ByteArrayOutputStream()
    val testConsole = new TestConsole(System.in, stream)
    val dwcli = createCommandLine(testConsole)
    val exitCode = dwcli.execute(
      "run",
      "-p", "name=Mariano",
      "-p", "lastname=Lischetti",
      "{fullName: params.name ++ \" \" ++  params.lastname}")
    exitCode shouldBe 0
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    val expected =
      """
        |{
        |  "fullName": "Mariano Lischetti"
        |}""".stripMarginAndNormalizeEOL.trim
    result shouldBe expected
  }
}
