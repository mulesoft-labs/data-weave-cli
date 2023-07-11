package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.VerifyWeaveCommand;
import org.mule.weave.dwnative.cli.commands.WeaveModule;
import org.mule.weave.dwnative.cli.commands.WeaveVerifyConfig;
import org.mule.weave.v2.core.io.FileHelper;
import org.mule.weave.v2.parser.ast.variables.NameIdentifier;
import org.mule.weave.v2.utils.DataWeaveVersion;
import picocli.CommandLine;
import scala.Option;

import java.io.File;
import java.util.concurrent.Callable;

import static org.mule.weave.cli.pico.AbstractPicoExecCommand.calculateRuntimeVersion;
import static org.mule.weave.cli.pico.AbstractPicoExecCommand.fileToString;

@CommandLine.Command(
        name = "validate",
        description = "Validate if a script is valid or not."
)
public class PicoValidateScript implements Callable<Integer> {

    Console console;

    @CommandLine.Option(names = {"--language-level"}, description = {"The version of DW to be supported."})
    protected String languageLevel = null;

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The DW script to be used",
            paramLabel = "SCRIPT"
    )
    String script = null;

    @CommandLine.Option(names = {"--file", "-f"}, description = "The Path to the dw file to run.")
    File dwFile = null;

    @CommandLine.Option(names = {"--input", "-i"}, description = "The name of an in implicit input.")
    String[] inputs = new String[0];

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec = null;


    public PicoValidateScript() {
        this.console = DefaultConsole$.MODULE$;
    }

    public PicoValidateScript(Console console) {
        this.console = console;
    }

    @Override
    public Integer call() {
        if ((script == null && dwFile == null) || (script != null && dwFile != null)) {
            String msg = "The script and file parameters are mutually exclusive, but one is required.";
            throw new CommandLine.ParameterException(spec.commandLine(), msg);
        }
        Option<DataWeaveVersion> dataWeaveVersionOption = calculateRuntimeVersion(languageLevel, spec);

        WeaveVerifyConfig config = new WeaveVerifyConfig(

                ((nr) -> {
                    if (script != null) {
                        return new WeaveModule(script, NameIdentifier.ANONYMOUS_NAME().name());
                    } else if (dwFile != null) {
                        return new WeaveModule(fileToString(dwFile), FileHelper.baseName(dwFile));
                    } else {
                        throw new RuntimeException("Missing dw script or main file");
                    }
                }),
                dataWeaveVersionOption,
                inputs

        );
        final VerifyWeaveCommand command = new VerifyWeaveCommand(config, console);
        return command.exec();
    }


}
