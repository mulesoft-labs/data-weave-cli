package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.ListSpellsCommand;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "list",
        description =
                "List all available spells."

)
public class PicoListSpells implements Callable<Integer> {

    Console console;

    public PicoListSpells() {
        this(DefaultConsole$.MODULE$);
    }

    public PicoListSpells(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() throws Exception {
        ListSpellsCommand command = new ListSpellsCommand(console);
        return command.exec();
    }
}

//PicCli wrapper

