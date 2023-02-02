package org.mule.weave.cli.pico;

import org.mule.weave.v2.version.ComponentVersion;
import picocli.CommandLine;

public class PicoVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {

        return new String[]{
                "Command Line : V" + ComponentVersion.nativeVersion(),
                "Runtime : V" + ComponentVersion.weaveVersion(),
        };
    }
}
