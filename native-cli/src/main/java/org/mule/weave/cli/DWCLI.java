package org.mule.weave.cli;


import org.mule.weave.cli.pico.AbstractPicoExecCommand;
import org.mule.weave.cli.pico.PicoAddWizard;
import org.mule.weave.cli.pico.PicoCreateSpell;
import org.mule.weave.cli.pico.PicoListSpells;
import org.mule.weave.cli.pico.PicoRepl;
import org.mule.weave.cli.pico.PicoRunScript;
import org.mule.weave.cli.pico.PicoRunSpell;
import org.mule.weave.cli.pico.PicoToDW1;
import org.mule.weave.cli.pico.PicoUpdateSpells;
import org.mule.weave.cli.pico.PicoVersionProvider;
import org.mule.weave.dwnative.cli.Console;
import org.mule.weave.dwnative.cli.DefaultConsole$;
import org.mule.weave.dwnative.cli.commands.ReplCommand;
import org.mule.weave.dwnative.cli.commands.ReplConfiguration;
import picocli.CommandLine;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.immutable.Map$;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public class DWCLI {
    public static void main(String[] args) {
        new DWCLI().run(args, DefaultConsole$.MODULE$);
    }

    public void run(String[] args, Console console) {
        int exitCode = new CommandLine(new DataWeaveCLIRunner(), new DWFactory(console))
                .execute(args);
        System.exit(exitCode);
    }

    @picocli.CommandLine.Command(
            mixinStandardHelpOptions = true,
            subcommands = {
                    PicoRunScript.class,
                    PicoAddWizard.class,
                    PicoToDW1.class,
                    PicoRunSpell.class,
                    CommandLine.HelpCommand.class,
                    PicoRepl.class
            },
            header = " ____   __  ____  __   _  _  ____   __   _  _  ____ \n" +
                    "(    \\ / _\\(_  _)/ _\\ / )( \\(  __) / _\\ / )( \\(  __)\n" +
                    " ) D (/    \\ )( /    \\\\ /\\ / ) _) /    \\\\ \\/ / ) _) \n" +
                    "(____/\\_/\\_/(__)\\_/\\_/(_/\\_)(____)\\_/\\_/ \\__/ (____)",
            footer = "Example:\n" +
                    "\n" +
                    " dw  run -i payload <fullPathToUser.json> \"output application/json --- payload\n" +
                    "filter (item) -> item.age > 17\"\n" +
                    "\n" +
                    " Documentation reference:\n" +
                    "\n" +
                    " https://docs.mulesoft.com/dataweave/latest/",
            versionProvider = PicoVersionProvider.class)
    public static class DataWeaveCLIRunner implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(System.out);
        }
    }


    public static class DWFactory implements CommandLine.IFactory {
        private final Console console;

        public DWFactory(Console console) {
            this.console = console;
        }

        @Override
        public <K> K create(Class<K> cls) throws Exception {
            if (List.class.isAssignableFrom(cls)) {
                return cls.cast(new ArrayList<Object>());
            } else if (SortedSet.class.isAssignableFrom(cls)) {
                return cls.cast(new TreeSet<Object>());
            } else if (Set.class.isAssignableFrom(cls)) {
                return cls.cast(new LinkedHashSet<Object>());
            } else if (Queue.class.isAssignableFrom(cls)) {
                return cls.cast(new LinkedList<Object>()); // ArrayDeque is only available since 1.6
            } else if (cls.isAssignableFrom(PicoRunScript.class)) {
                return cls.cast(new PicoRunScript(console));
            } else if (cls.isAssignableFrom(PicoAddWizard.class)) {
                return cls.cast(new PicoAddWizard(console));
            } else if (cls.isAssignableFrom(PicoCreateSpell.class)) {
                return cls.cast(new PicoCreateSpell(console));
            } else if (cls.isAssignableFrom(PicoListSpells.class)) {
                return cls.cast(new PicoListSpells(console));
            } else if (cls.isAssignableFrom(PicoRunSpell.class)) {
                return cls.cast(new PicoRunSpell(console));
            } else if (cls.isAssignableFrom(PicoUpdateSpells.class)) {
                return cls.cast(new PicoUpdateSpells(console));
            } else if (cls.isAssignableFrom(PicoRepl.class)) {
                return cls.cast(new PicoRepl(console));
            } else if (Map.class.isAssignableFrom(cls)) {
                try {
                    return cls.cast(cls.getDeclaredConstructor().newInstance());
                } catch (Exception var5) {
                    return cls.cast(new LinkedHashMap());
                }
            } else {
                return cls.newInstance();
            }
        }
    }
}
