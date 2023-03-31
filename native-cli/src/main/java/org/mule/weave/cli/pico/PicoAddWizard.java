package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.AddWizardCommand;
import org.mule.weave.dwnative.cli.commands.CloneWizardConfig;
import picocli.CommandLine;

import java.util.concurrent.Callable;



@CommandLine.Command(
        name = "add",
        description =
                "Adds a new Wizard to your network of trusted wizards."

)
public class PicoAddWizard implements Callable<Integer> {

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The name of the Wizard to be added. For example `leansh`"
    )
    String wizardName = null;

    Console console;

    public PicoAddWizard() {
        this(DefaultConsole$.MODULE$);
    }

    public PicoAddWizard(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() throws Exception {
        AddWizardCommand command = new AddWizardCommand(new CloneWizardConfig(wizardName), console);
        return command.exec();
    }
}
