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
  val PARAMETER = "parameter"
  val SPELL = "spell"
  val SILENT = "silent"
  val UNTRUSTED_CODE = "untrusted-code"
  val UPDATE_GRIMOIRES = "update-grimoires"
  val VERBOSE = "verbose"
  val VERSION = "version"
  val EXPERIMENTAL_TAG = "[Experimental]"
  
  val OPTIONS: CliOptions = {
    val options = new CliOptions()
    options.addOption(null, HELP, false, "Shows the help.")
    
    options.addOption(Option.builder("p")
      .longOpt(PARAMETER)
      .hasArgs()
      .numberOfArgs(2)
      .argName("param-name param-value")
      .desc("Parameter to be passed.")
      .build())
    
    options.addOption(Option.builder("i")
      .longOpt(INPUT)
      .hasArgs()
      .numberOfArgs(2)
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
    
    options.addOption(null, LIST_SPELLS, false, s"$EXPERIMENTAL_TAG List all the available spells.")
    
    options.addOption(Option.builder("s")
      .longOpt(SPELL)
      .hasArg(true)
      .argName("spell-name")
      .desc(s"$EXPERIMENTAL_TAG Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.")
      .build())


    options.addOption(Option.builder()
      .longOpt(SILENT)
      .desc(s"Executes the script in silent mode, where all info messages is not going to be shown.")
      .build())
  
    options.addOption(Option.builder()
      .longOpt(LOCAL_SPELL)
      .hasArg(true)
      .argName("spell-folder")
      .desc(s"$EXPERIMENTAL_TAG Executes a local folder spell.")
      .build())
    
    options.addOption(Option.builder()
      .longOpt(NEW_SPELL)
      .hasArg(true)
      .argName("spell-name")
      .desc(s"$EXPERIMENTAL_TAG Create a new spell.")
      .build()
    )
    
    options.addOption(Option.builder()
      .longOpt(ADD_WIZARD)
      .hasArg(true)
      .argName("wizard-name")
      .desc(s"$EXPERIMENTAL_TAG Downloads wizard grimoire so that its spell are accessible.")
      .build()
    )
    
    options.addOption(null, UPDATE_GRIMOIRES, false, s"$EXPERIMENTAL_TAG Update all wizard grimoires.")
    
    options
  }
}
