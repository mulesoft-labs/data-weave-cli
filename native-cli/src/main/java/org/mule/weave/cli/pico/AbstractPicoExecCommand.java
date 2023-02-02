package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.v2.utils.DataWeaveVersion;
import picocli.CommandLine;
import scala.Option;
import scala.Predef;
import scala.Tuple2;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;

public abstract class AbstractPicoExecCommand implements Callable<Integer> {
    protected Console console;
    @CommandLine.Option(names = {"--param", "-p"}, description = {"Parameter to be passed. All input parameters are accessible through the variable `params` of type object."})
    protected java.util.Map<String, String> params = null;
    @CommandLine.Option(names = {"--input", "-i"}, description = {"Declares a new input."}, paramLabel = "<Name=File>")
    protected java.util.Map<String, File> inputs = null;
    @CommandLine.Option(names = {"--verbose", "-v"}, description = {"Run the script as untrusted, which means that the script has no privileges."}, defaultValue = "false")
    protected boolean verbose = false;
    @CommandLine.Option(names = {"--untrusted"}, description = {"Run the script as untrusted, which means that the script has no privileges."}, defaultValue = "false")
    protected boolean untrusted = false;
    @CommandLine.Option(names = {"--language-level"}, description = {"The version of DW to be supported."})
    protected String languageLevel = null;
    @CommandLine.Option(
            names = {"--privileges"},
            description = {"A list of all allowed runtime privileges for this execution to have."},
            split = ","
    )
    protected java.util.List<String> privileges = null;
    @CommandLine.Option(names = {"--silent", "-s"}, description = {"Run in silent mode it reduces the logging on the standard output."})
    protected boolean silent = false;
    @CommandLine.Spec
    protected CommandLine.Model.CommandSpec spec;

    public AbstractPicoExecCommand(Console console) {
        this.console = console;
    }

    public static <A, B> Map<A, B> toScalaMap(java.util.Map<A, B> m) {
        return JavaConverters.mapAsScalaMapConverter(m).asScala().toMap(
                Predef.<Tuple2<A, B>>conforms()
        );
    }

    public String fileToString(File f) {
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Integer call() {
        if (silent) {
            console.enableSilent();
        }

        if (verbose) {
            console.enableDebug();
        }
        if (untrusted) {
            privileges = new java.util.ArrayList<String>();
        }
        return doCall();
    }

    protected Option<DataWeaveVersion> calculateRuntimeVersion() {
        Option<DataWeaveVersion> dataWeaveVersionOption;
        try {
            dataWeaveVersionOption = Option.apply(languageLevel).map((s) -> DataWeaveVersion.apply(s));
            if (dataWeaveVersionOption.isDefined()) {
                DataWeaveVersion dataWeaveVersion = dataWeaveVersionOption.get();
                DataWeaveVersion currentVersion = DataWeaveVersion.apply();
                if (dataWeaveVersion.$greater(currentVersion)) {
                    throw new CommandLine.ParameterException(spec.commandLine(), "Invalid language level, cannot be higher than " + currentVersion.toString());
                }
            }
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Invalid language-level option value : `" + languageLevel + "`.");
        }
        return dataWeaveVersionOption;
    }

    protected abstract Integer doCall();
}
