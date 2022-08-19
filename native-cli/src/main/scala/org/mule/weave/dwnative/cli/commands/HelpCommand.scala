package org.mule.weave.dwnative.cli.commands

import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.cli.Options

import java.io.PrintWriter
import java.util.Comparator

class HelpCommand(console: Console) extends WeaveCommand {

  override def exec(): Int = {
    printHelp()
    ExitCodes.SUCCESS
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
    formatter.setOptionComparator(new Comparator[Option] {
      
      private def getKey(opt: Option): String = { 
        // if 'opt' is null, then it is a 'long' option
        if (opt.getOpt == null) opt.getLongOpt else opt.getOpt
      }
      
      private def isExperimentalOption(opt: Option): Boolean = {
        opt.getDescription != null && opt.getDescription.contains(Options.EXPERIMENTAL_TAG)
      } 
      
      override def compare(opt1: Option, opt2: Option): Int = {
        val isOpt1Experimental = isExperimentalOption(opt1)
        val isOpt2Experimental = isExperimentalOption(opt2)
        (isOpt1Experimental, isOpt2Experimental) match {
          case (true, false) => 1
          case (false, true) => -1
          case (_, _) => getKey(opt1).compareToIgnoreCase(getKey(opt2))
        }
        
      }
    })
    formatter.printHelp(pw, formatter.getWidth, "dw", header, Options.OPTIONS, formatter.getLeftPadding, formatter.getDescPadding, footer, true)
    pw.flush()
  }
}

object HelpCommand {
  def apply(console: Console): HelpCommand = new HelpCommand(console)
}
