package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.MigrateDW1Command;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;


//PicCli wrapper
@CommandLine.Command(
        name = "migrate",
        description = "Translates a DW1 script into a DW2 script."
)
public class PicoMigrate implements Callable<Integer> {

    Console console;

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The path to the dw1 file."
    )
    private File dw1File = null;

    public PicoMigrate() {
        this(DefaultConsole$.MODULE$);
    }

    public PicoMigrate(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() throws Exception {
        MigrateDW1Command migrateDW1Command = new MigrateDW1Command(dw1File.getAbsolutePath(), console);
        return migrateDW1Command.exec();
    }
}
