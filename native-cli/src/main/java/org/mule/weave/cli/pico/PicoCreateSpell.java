package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.CreateSpellCommand;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "new-spell",
        description =
                "Creates a new spell with the given name."

)
public class PicoCreateSpell implements Callable<Integer> {

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The name of the Spell to be created. For example `list-releases`"
    )
    String spellName = null;

    Console console;


    public PicoCreateSpell() {
        this(DefaultConsole$.MODULE$);
    }
    public PicoCreateSpell(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() throws Exception {
        CreateSpellCommand command = new CreateSpellCommand(spellName, console);
        return command.exec();

    }
}
