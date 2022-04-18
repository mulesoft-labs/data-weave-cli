package org.mule.weave.dwnative.cli.commands

import org.mule.weave.dwnative.cli.Console

class UsageCommand(console: Console) extends WeaveCommand {
  override def exec(): Int = {
    console.info(usages())
    0
  }

  def usages(): String = {
    """
      |
      |.........................................................................
      |.%%%%%....%%%%...%%%%%%...%%%%...%%...%%..%%%%%%...%%%%...%%..%%..%%%%%%.
      |.%%..%%..%%..%%....%%....%%..%%..%%...%%..%%......%%..%%..%%..%%..%%.....
      |.%%..%%..%%%%%%....%%....%%%%%%..%%.%.%%..%%%%....%%%%%%..%%..%%..%%%%...
      |.%%..%%..%%..%%....%%....%%..%%..%%%%%%%..%%......%%..%%...%%%%...%%.....
      |.%%%%%...%%..%%....%%....%%..%%...%%.%%...%%%%%%..%%..%%....%%....%%%%%%.
      |.........................................................................
      |
      |
      |Usage:
      |
      |dw [-p <weavePath>]? [-i <name> <path>]* [-v]? [-o <outputPath>]? [[-f <filePath>] | <scriptContent>]
      |
      |Arguments Detail:
      |
      | --prop or -p       | Property to be passed.
      | --input or -i      | Declares a new input.
      | --output or -o     | Specifies output file for the transformation if not standard output will be used.
      | --file or -f       | Path to the file.
      | --eval             | Evaluates the script instead of writing it.
      | --version          | The version of the CLI and Runtime.
      | --verbose or -v    | Enable Verbose Mode.
      | --untrusted-code    | Enable Verbose Mode.
      | --privileges       | "A comma separated set of the privileges for the script execution..
      | --list-spells      | [Experimental] List all the available spells.
      | --spell or -s      | [Experimental] Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.
      | --local-spell      | [Experimental] Executes a local folder spell.
      | --new-spell        | [Experimental] Create a new spell.
      | --add-wizard       | [Experimental] Downloads wizard grimoire so that its spell are accessible.
      | --update-grimoires | [Experimental] Update all wizard grimoires.
      |
      |
      | Example:
      |
      | dw -i payload <fullpathToUser.json> "output application/json --- payload filter (item) -> item.age > 17"
      |
      | Documentation reference:
      |
      | https://docs.mulesoft.com/dataweave/latest/
    """.stripMargin
  }

}
