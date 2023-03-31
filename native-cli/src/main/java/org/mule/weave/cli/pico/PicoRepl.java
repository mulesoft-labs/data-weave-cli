package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.commands.ReplCommand;
import org.mule.weave.dwnative.cli.commands.ReplConfiguration;
import picocli.CommandLine;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;

import java.io.File;
import java.util.Optional;

@CommandLine.Command(
        name = "repl",
        description = "Starts the DW repl."
)
public class PicoRepl extends AbstractPicoExecCommand {


    public PicoRepl(Console console) {
        super(console);
    }

    @Override
    protected Integer doCall() {
        ReplConfiguration replConfiguration = new ReplConfiguration(
                new String[0],
                Option.empty(),
                Optional.ofNullable(params).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, String>empty()),
                Optional.ofNullable(inputs).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, File>empty()),
                Optional.ofNullable(literalInput).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, String>empty()),
                Option.apply(privileges).map((s) -> JavaConverters.asScalaBuffer(s).toSeq()),
                calculateRuntimeVersion(languageLevel, spec)
        );
        ReplCommand replCommand = new ReplCommand(replConfiguration, console);
        return replCommand.exec();
    }

}
