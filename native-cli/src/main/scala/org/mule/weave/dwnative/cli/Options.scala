package org.mule.weave.dwnative.cli

import org.apache.commons.cli.Option
import org.apache.commons.cli.{Options => CliOptions}

object Options {
  val ADD_WIZARD = "add-wizard"
  val EVAL = "eval"
  val FILE = "file"
  val HELP = "help"
  val INPUT = "input"
  val LIST_SPELLS = "list-spells"
  val LOCAL_SPELL = "local-spell"
  val NEW_SPELL = "new-spell"
  val OUTPUT = "output"
  val PRIVILEGES = "privileges"
  val PROFILE = "profile"
  val PROPERTY = "property"
  val SPELL = "spell"
  val UNTRUSTED_CODE = "untrusted-code"
  val UPDATE_GRIMOIRES = "update-grimoires"
  val VERBOSE = "verbose"
  val VERSION = "version"
  
  val OPTIONS: CliOptions = {
    val options = new CliOptions()
    options.addOption(null, HELP, false, "Shows the help.")
    
    options.addOption(Option.builder("p")
      .longOpt(PROPERTY)
      .hasArgs()
      .numberOfArgs(2) // Option.UNLIMITED_VALUES)
      .argName("property-name property-value")
      .desc("Property to be passed.")
      .build())
    
    options.addOption(Option.builder("i")
      .longOpt(INPUT)
      .hasArgs()
      .numberOfArgs(2) // Option.UNLIMITED_VALUES)
      .argName("input-name input-path")
      .desc("Declares a new input.")
      .build())
    
    options.addOption(Option.builder("o")
      .longOpt(OUTPUT)
      .hasArg()
      .argName("output-path")
      .desc("Specifies output file for the transformation if not standard output will be used.")
      .build())
    
    options.addOption("f", "file", true, "Path to the file.")

    options.addOption(Option.builder("f")
      .longOpt(FILE)
      .hasArg()
      .argName("file-path")
      .desc("Specifies output file for the transformation if not standard output will be used.")
      .build())
    
    options.addOption(null, PROFILE, false, "Profile the script execution.")
    
    options.addOption(null, EVAL, false, "Evaluates the script instead of writing it.")
    
    options.addOption(null, VERSION, false, "The version of the CLI and Runtime.")
    
    options.addOption("v", VERBOSE, false, "Enable verbose mode.")
    
    options.addOption(null, UNTRUSTED_CODE, false, "Run the script as untrusted, which means that the script has no privileges.")

    options.addOption(Option.builder()
      .longOpt(PRIVILEGES)
      .hasArg()
      .argName("privileges")
      .desc("A comma separated set of the privileges for the script execution.")
      .build())
    
    options.addOption(null, LIST_SPELLS, false, "[Experimental] List all the available spells.")
    
    options.addOption(Option.builder("s")
      .longOpt(SPELL)
      .hasArg(true)
      .argName("spell-name")
      .desc("[Experimental] Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.")
      .build())
  
    options.addOption(Option.builder()
      .longOpt(LOCAL_SPELL)
      .hasArg(true)
      .argName("spell-folder")
      .desc("[Experimental] Executes a local folder spell.")
      .build())
    
    options.addOption(Option.builder()
      .longOpt(NEW_SPELL)
      .hasArg(true)
      .argName("spell-name")
      .desc("[Experimental] Create a new spell.")
      .build()
    )
    
    options.addOption(Option.builder()
      .longOpt(ADD_WIZARD)
      .hasArg(true)
      .argName("wizard-name")
      .desc("[Experimental] Downloads wizard grimoire so that its spell are accessible.")
      .build()
    )
    
    options.addOption(null, UPDATE_GRIMOIRES, false, "[Experimental] Update all wizard grimoires.")
    
    options
  }
}
