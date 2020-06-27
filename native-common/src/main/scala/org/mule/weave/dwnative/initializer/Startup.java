package org.mule.weave.dwnative.initializer;

/**
 * This class is use to link everything that we want to startup at
 */
public class Startup {
    static {
        System.out.println("Startup ->  Initializing Modules for performance.");
        NativeSystemModuleComponents.start();
    }
}
