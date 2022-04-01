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
      |dw [-p <weavePath>]? [-i <name> <path>]* [-v]? [-o <outputPath>]? [[-f <filePath>] | [-m <nameIdentifier>] | <scriptContent>]
      |
      |Arguments Detail:
      |
      | --watch            | Keep the cli up and watch the used files for modifications and re execute
      | --path             | Path of jars or directories where weave files are being searched.
      | --prop or -p       | Property to be passed.
      | --input or -i      | Declares a new input.
      | --list-spells      | List all the available spells
      | --spell or -s      | Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.
      | --update-grimoires | Update all wizard grimoires
      | --add-wizard       | Downloads wizard grimoire so that its spell are accessible
      | --local-spell      | Executes a local folder spell
      | --new-spell        | Create a new spell
      | --output or -o     | Specifies output file for the transformation if not standard output will be used.
      | --main or -m       | The full qualified name of the mapping to be execute.
      | --file or -f       | Path to the file
      | --eval             | Evaluates the script instead of writing it
      | --version          | The version of the CLI and Runtime
      | --clean-cache      | Cleans the cache where all artifacts are being downloaded this force to download all artifacts every time
      | --verbose or -v    | Enable Verbose Mode.
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
