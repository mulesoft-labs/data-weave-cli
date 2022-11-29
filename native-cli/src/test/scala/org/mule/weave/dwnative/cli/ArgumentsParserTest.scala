package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.cli.commands.CreateSpellCommand
import org.mule.weave.dwnative.cli.commands.HelpCommand
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.File

class ArgumentsParserTest extends FreeSpec with Matchers {
  
  private def getScriptFolder(scriptFolder: String): File = {
    val url = getClass.getClassLoader.getResource(s"scripts/$scriptFolder")
    val file = new File(url.getFile)
    file
  }

  "should parse correctly help argument" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("--help"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[HelpCommand])
  }

  "should parse correctly a local script with multiple inputs and properties" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val simpleScriptFolder = getScriptFolder("simpleScript")
    val basePath = simpleScriptFolder.getAbsolutePath
    val value = parser.parse(Array(
      "--file", s"$basePath${File.separator}script.dwl",
      "-i", "in0", s"$basePath${File.separator}in0.json",
      "-i", "in1", s"$basePath${File.separator}in1.json",
      "-p", "p0", "property-0",
      "-p", "p1", "property-1",
      "-o", s"$basePath${File.separator}out.json"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should parse correctly a simple executable" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("1 to 10"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should parse correctly a simple executable with input" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val simpleScriptFolder = getScriptFolder("simpleScript")
    val basePath = simpleScriptFolder.getAbsolutePath
    val value = parser.parse(Array(
      "-i", "in1", s"$basePath${File.separator}in1.json",
      "payload"
    ))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }
  
  "should parse correctly a local spell arg" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val lib = TestUtils.getMyLocalSpellWithLib
    val value = parser.parse(Array("--local-spell", lib.getAbsolutePath))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should set the watch command correctly" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val lib = TestUtils.getMyLocalSpellWithLib
    val value = parser.parse(Array("--local-spell", lib.getAbsolutePath))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should parse correctly when using literal script" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("'Test'"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should parse new-spell correctly" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("--new-spell", "Test"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[CreateSpellCommand])
    val runWeaveCommand = commandToRun.asInstanceOf[CreateSpellCommand]
    assert(runWeaveCommand.spellName == "Test")
  }

  "should parse language-level correctly" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("'Test'", "--language-level", "2.4"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

  "should fail parsing unrecognized argument" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("-o", "/tmp/out.json", "--parameter", "p1", "p2", "p3", "1 to 10" ))
    assert(value.isRight)
    val message = value.right.get
    message shouldBe "Invalid argument p3"
  }
}
