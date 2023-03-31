package org.mule.weave.cli.pico;

import picocli.CommandLine;

@CommandLine.Command(
        name = "wizard",
        description = "Wizard actions.",
        subcommands = {
                PicoAddWizard.class
        }
)
public class PicoWizard implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }
}
