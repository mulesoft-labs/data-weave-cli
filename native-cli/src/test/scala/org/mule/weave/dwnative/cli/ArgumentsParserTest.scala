package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.cli.commands.CreateSpellCommand
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand
import org.mule.weave.dwnative.cli.commands.WeaveCommand
import org.mule.weave.dwnative.cli.commands.WeaveModule
import org.scalatest.FreeSpec

class ArgumentsParserTest extends FreeSpec {

  "should parse correctly a local spell arg" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val lib = TestUtils.getMyLocalSpellWithLib
    val value = parser.parse(Array("--local-spell", lib.getAbsolutePath))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
    val runWeaveCommand = commandToRun.asInstanceOf[RunWeaveCommand]
    assert(runWeaveCommand.config.filesToWatch.size == 2)
    assert(!runWeaveCommand.config.watch)
  }

  "should set the watch command correctly" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val lib = TestUtils.getMyLocalSpellWithLib
    val value = parser.parse(Array("--watch","--local-spell", lib.getAbsolutePath))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
    val runWeaveCommand = commandToRun.asInstanceOf[RunWeaveCommand]
    assert(runWeaveCommand.config.filesToWatch.size == 2)
    assert(runWeaveCommand.config.watch)
  }


  "should parse correctly when using literal script" in {
    val parser = new CLIArgumentsParser(new TestConsole())
    val value = parser.parse(Array("'Test'"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
    val runWeaveCommand = commandToRun.asInstanceOf[RunWeaveCommand]
    assert(runWeaveCommand.config.filesToWatch.isEmpty)
    assert(!runWeaveCommand.config.watch)
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

  "should parse correctly a main " in {
    val parser: CLIArgumentsParser = new CLIArgumentsParser(new TestConsole())
    val value: Either[WeaveCommand, String] = parser.parse(Array("--main", "Test.dwl"))
    assert(value.isLeft)
    val commandToRun = value.left.get
    assert(commandToRun.isInstanceOf[RunWeaveCommand])
  }

}
