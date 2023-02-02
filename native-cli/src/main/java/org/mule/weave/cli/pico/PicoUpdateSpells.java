package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.UpdateAllGrimoires;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "update",
        description = "Update all spells to the latest one."

)
public class PicoUpdateSpells implements Callable<Integer> {

    Console console;

    public PicoUpdateSpells() {
        this(DefaultConsole$.MODULE$);
    }

    public PicoUpdateSpells(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() throws Exception {
        UpdateAllGrimoires command = new UpdateAllGrimoires(console);
        return command.exec();
    }
}

//PicCli wrapper

