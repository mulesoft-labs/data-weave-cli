package org.mule.weave.dwnative;

/**
 * This class is use to link everything that we want to startup at
 */
public class Startup {
    static {
        NativeSystemModuleComponents.start();
    }
}
