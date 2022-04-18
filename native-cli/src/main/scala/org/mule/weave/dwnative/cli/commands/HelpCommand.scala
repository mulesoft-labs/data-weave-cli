package org.mule.weave.dwnative.cli.commands

import org.apache.commons.cli.HelpFormatter
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.Options

import java.io.PrintWriter

class HelpCommand(console: Console) extends WeaveCommand {

  override def exec(): Int = {
    printHelp()
    0
  }

  private def printHelp(): Unit = {
    val formatter = new HelpFormatter()
    val header = """
                   |
                   |.........................................................................
                   |.%%%%%....%%%%...%%%%%%...%%%%...%%...%%..%%%%%%...%%%%...%%..%%..%%%%%%.
                   |.%%..%%..%%..%%....%%....%%..%%..%%...%%..%%......%%..%%..%%..%%..%%.....
                   |.%%..%%..%%%%%%....%%....%%%%%%..%%.%.%%..%%%%....%%%%%%..%%..%%..%%%%...
                   |.%%..%%..%%..%%....%%....%%..%%..%%%%%%%..%%......%%..%%...%%%%...%%.....
                   |.%%%%%...%%..%%....%%....%%..%%...%%.%%...%%%%%%..%%..%%....%%....%%%%%%.
                   |.........................................................................
    """.stripMargin

    val footer = """
                   |
                   | Example:
                   |
                   | dw -i payload <fullPathToUser.json> "output application/json --- payload filter (item) -> item.age > 17"
                   |
                   | Documentation reference:
                   |
                   | https://docs.mulesoft.com/dataweave/latest/
    """.stripMargin
    val pw = new PrintWriter(console.out)
    formatter.printHelp(pw, formatter.getWidth, "dw", header, Options.OPTIONS, formatter.getLeftPadding, formatter.getDescPadding, footer, true)
    pw.flush()
  }
}

object HelpCommand {
  def apply(console: Console): HelpCommand = new HelpCommand(console)
}
