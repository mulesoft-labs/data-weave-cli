package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.cli.commands.CreateSpellCommand
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand
import org.scalatest.FreeSpec

class ArgumentsParserTest extends FreeSpec {

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
}
