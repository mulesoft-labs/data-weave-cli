package org.mule.weave.dwnative.lib;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.mule.weave.dwnative.DataWeaveUtils;
import org.mule.weave.dwnative.NativeRuntime;
import org.mule.weave.dwnative.WeaveExecutionResult;
import org.mule.weave.dwnative.WeaveInput;
import org.mule.weave.v2.module.reader.SourceProvider$;

import java.io.File;


public class DataWeaveNativeLibrary {

    private static NativeRuntime runtime = new NativeRuntime(DataWeaveUtils.getLibPathHome(), new File[0]);

    @CEntryPoint(name = "runDW")
    public static CCharPointer runDW(IsolateThread thread, CCharPointer transform) {
        final String script = CTypeConversion.toJavaString(transform);
        final WeaveExecutionResult run = runtime.run(script, new WeaveInput[0]);
        return CTypeConversion.toCString(run.result()).get();
    }

    @CEntryPoint(name = "runDW1")
    public static CCharPointer runDW1(IsolateThread thread, CCharPointer transform, CCharPointer inputName, CCharPointer input) {
        final String script = CTypeConversion.toJavaString(transform);
        final String inputNameStr = CTypeConversion.toJavaString(inputName);
        final String inputContentStr = CTypeConversion.toJavaString(input);
        final WeaveExecutionResult run = runtime.run(script, new WeaveInput[]{new WeaveInput(inputNameStr, SourceProvider$.MODULE$.apply(inputContentStr))});
        return CTypeConversion.toCString(run.result()).get();
    }

    @CEntryPoint(name = "runDW2")
    public static CCharPointer runDW2(IsolateThread thread, CCharPointer transform, CCharPointer inputName, CCharPointer input, CCharPointer inputName2, CCharPointer input2) {
        final String script = CTypeConversion.toJavaString(transform);
        final String inputNameStr = CTypeConversion.toJavaString(inputName);
        final String inputContentStr = CTypeConversion.toJavaString(input);

        final String inputNameStr2 = CTypeConversion.toJavaString(inputName2);
        final String inputContentStr2 = CTypeConversion.toJavaString(input2);


        final WeaveExecutionResult run = runtime.run(script, new WeaveInput[]{
                new WeaveInput(inputNameStr, SourceProvider$.MODULE$.apply(inputContentStr)),
                new WeaveInput(inputNameStr2, SourceProvider$.MODULE$.apply(inputContentStr))
        });

        return CTypeConversion.toCString(run.result()).get();
    }

}
