package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand;
import org.mule.weave.dwnative.cli.commands.WeaveModule;
import org.mule.weave.dwnative.cli.commands.WeaveRunnerConfig;
import org.mule.weave.v2.io.FileHelper;
import org.mule.weave.v2.parser.ast.variables.NameIdentifier;
import org.mule.weave.v2.utils.DataWeaveVersion;
import picocli.CommandLine;
import scala.None$;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;

import java.io.File;
import java.util.Optional;

@CommandLine.Command(
        name = "run",
        description =
                "Runs provided DW script."

)
public class PicoRunScript extends AbstractPicoRunCommand {

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The DW script to be used",
            paramLabel = "SCRIPT"
    )
    String script = null;

    @CommandLine.Option(names = {"--file", "-f"}, description = "The Path to the dw file to run.")
    File dwFile = null;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec = null;


    public PicoRunScript() {
        super(DefaultConsole$.MODULE$);
    }

    public PicoRunScript(Console console) {
        super(console);
    }

    @Override
    protected Integer doCall() {
        if ((script == null && dwFile == null) || (script != null && dwFile != null)) {
            String msg = "The script and file parameters are mutually exclusive, but one is required.";
            throw new CommandLine.ParameterException(spec.commandLine(), msg);
        }

        Option<DataWeaveVersion> dataWeaveVersionOption = calculateRuntimeVersion();
        final WeaveRunnerConfig config = new WeaveRunnerConfig(
                new String[0],
                eval,
                ((nr) -> {
                    if (script != null) {
                        return new WeaveModule(script, NameIdentifier.ANONYMOUS_NAME().name());
                    } else if (dwFile != null) {
                        return new WeaveModule(fileToString(dwFile), FileHelper.baseName(dwFile));
                    } else {
                        throw new RuntimeException("Missing dw script or main file");
                    }
                }),
                None$.empty(),
                Optional.ofNullable(params).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, String>empty()),
                Optional.ofNullable(inputs).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, File>empty()),
                Optional.ofNullable(literalInput).map((s) -> toScalaMap(s)).orElse(Map$.MODULE$.<String, String>empty()),
                Option.apply(output).map((s) -> s.getAbsolutePath()),
                Option.apply(privileges).map((s) -> JavaConverters.asScalaBuffer(s).toSeq()),
                dataWeaveVersionOption
        );
        final RunWeaveCommand command = new RunWeaveCommand(config, console);
        return command.exec();
    }
}
