package org.mule.weave.cli.pico;

import org.mule.weave.dwnative.NativeRuntime;
import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.commands.AddWizardCommand;
import org.mule.weave.dwnative.cli.commands.CloneWizardConfig;
import org.mule.weave.dwnative.cli.commands.RunWeaveCommand;
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireCommand;
import org.mule.weave.dwnative.cli.commands.UpdateGrimoireConfig;
import org.mule.weave.dwnative.cli.commands.WeaveModule;
import org.mule.weave.dwnative.cli.commands.WeaveRunnerConfig;
import org.mule.weave.dwnative.cli.utils.SpellsUtils;
import org.mule.weave.dwnative.dependencies.DependencyResolutionResult;
import org.mule.weave.dwnative.dependencies.SpellDependencyManager;
import org.mule.weave.v2.parser.ast.variables.NameIdentifier;
import org.mule.weave.v2.sdk.NameIdentifierHelper;
import org.mule.weave.v2.utils.DataWeaveVersion;
import picocli.CommandLine;
import scala.Function1;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;

import java.io.File;
import java.util.Optional;

@CommandLine.Command(
        name = "spell",
        description = "Runs the specified Spell.",
        subcommands = {
                PicoCreateSpell.class,
                PicoListSpells.class,
                PicoUpdateSpells.class
        }
)
public class PicoRunSpell extends AbstractPicoRunCommand {

    @CommandLine.Parameters(
            index = "0",
            arity = "0..1",
            description = "The name of the Spell to be created. For example `playground`",
            paramLabel = "SPELL-ID"
    )
    String spell = null;

    @CommandLine.Option(names = {"--local", "-l"}, description = "Will look for the spell in the current directory.")
    boolean local;

    @CommandLine.Option(names = {"--spell-home"}, description = "Home folder where to search local spells.", defaultValue = ".")
    String spellHome;


    public PicoRunSpell(Console console) {
        super(console);
    }


    @Override
    protected Integer doCall() {
        final SpellsUtils utils = new SpellsUtils(console);

        if (spell == null || spell.isBlank()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Spell parameter required");
        }

        String wizard = "";
        if (spell.contains("/")) {
            wizard = spell.split("/")[0];
        }

        String spellName = spell;
        if (spell.contains("/")) {
            spellName = spell.split("/")[1];
        }

        var fileName = "Main.dwl";
        final NameIdentifier nameIdentifier;
        if (spellName.contains("@")) {
            String[] spellParts = spellName.split("@");
            spellName = spellParts[0];
            nameIdentifier = NameIdentifier.apply(spellParts[spellParts.length - 1], Option.empty());
            fileName = NameIdentifierHelper.toWeaveFilePath(nameIdentifier, File.pathSeparator);
        } else {
            nameIdentifier = NameIdentifier.apply("Main", Option.empty());
        }

        int lastUpdate = utils.daysSinceLastUpdate();
        if (lastUpdate > 30) {
            console.info("Your spells are getting old. " + lastUpdate + " days since last update. Please run \n dw update-spells");
        }

        File spellFolder;

        if (!local) {
            File wizardGrimoire = utils.grimoireFolder(wizard);
            if (!wizardGrimoire.exists()) {
                new AddWizardCommand(new CloneWizardConfig(wizard), console).exec();
            }
            wizardGrimoire = utils.grimoireFolder(wizard);
            String wizardName = "weave";
            if (wizard != null) {
                wizardName = wizard;
            }
            if (!wizardGrimoire.exists()) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Unable to get Wise `" + wizardName + "'s` Grimoire.");
            }
            spellFolder = new File(wizardGrimoire, spellName);
            if (!spellFolder.exists()) {
                new UpdateGrimoireCommand(new UpdateGrimoireConfig(wizardGrimoire), console).exec();
            }
            if (!spellFolder.exists()) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Unable find `" + spellName + "` in Wise `" + wizardName + "'s` Grimoire.");
            }
        } else {
            spellFolder = new File(spellHome, spellName);
            if (!spellFolder.exists()) {
                throw new CommandLine.ParameterException(spec.commandLine(), "Unable find local spell :`" + spellName + "`.");
            }
        }

        final File srcFolder = new File(spellFolder, "src");
        final File mainFile = new File(srcFolder, fileName);
        if (!mainFile.isFile()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "Unable find `" + fileName + "` in the spell: `" + spellName + ".");
        }

        final SpellDependencyManager manager = new SpellDependencyManager(spellFolder, console);
        final Function1<NativeRuntime, DependencyResolutionResult[]> resolver = (nr) -> manager.resolveDependencies(nr);

        Option<DataWeaveVersion> dataWeaveVersionOption = calculateRuntimeVersion();

        final WeaveRunnerConfig config = WeaveRunnerConfig.apply(
                new String[]{srcFolder.getAbsolutePath()},
                eval,
                (st) -> {
                    return new WeaveModule(fileToString(mainFile), nameIdentifier.toString());
                },
                Option.apply(resolver),
                Optional.ofNullable(params).map(AbstractPicoRunCommand::toScalaMap).orElse(Map$.MODULE$.<String, String>empty()),
                Optional.ofNullable(inputs).map(AbstractPicoRunCommand::toScalaMap).orElse(Map$.MODULE$.<String, File>empty()),
                Option.apply(output).map(File::getAbsolutePath),
                Option.apply(privileges).map((s) -> JavaConverters.asScalaBuffer(s).toSeq()),
                dataWeaveVersionOption
        );
        final RunWeaveCommand command = new RunWeaveCommand(config, console);
        return command.exec();
    }


}