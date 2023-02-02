package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import picocli.CommandLine;

import java.io.File;

public abstract class AbstractPicoRunCommand extends AbstractPicoExecCommand {

    public AbstractPicoRunCommand(Console console) {
        super(console);
    }


    @CommandLine.Option(names = {"--output", "-o"}, description = {"Specifies output file for the transformation if not standard output will be used."})
    File output = null;

    @CommandLine.Option(names = "--eval", description = "Executes the script but it doesn't use the writer. This is useful when launching a webserver.")
    boolean eval = false;

}
