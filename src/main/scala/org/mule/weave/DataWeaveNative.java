package org.mule.weave;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

public class DataWeaveNative {

    private static NativeRuntime runtime = new NativeRuntime();

    @CEntryPoint(name = "runDW")
    public static CCharPointer runDW(IsolateThread thread, CCharPointer transform) {
        final String script = CTypeConversion.toJavaString(transform);
        try {
            final WeaveResult run = runtime.run(script, new WeaveInput[0]);
            return CTypeConversion.toCString(run.result()).get();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return CTypeConversion.toCString("No").get();
    }

    @CEntryPoint(name = "runDW1")
    public static CCharPointer runDW1(IsolateThread thread, CCharPointer transform, CCharPointer inputName, CCharPointer input) {
        final String script = CTypeConversion.toJavaString(transform);
        final String inputNameStr = CTypeConversion.toJavaString(inputName);
        final String inputContentStr = CTypeConversion.toJavaString(input);
        final WeaveResult run = runtime.run(script, new WeaveInput[]{new WeaveInput(inputNameStr, inputContentStr)});
        return CTypeConversion.toCString(run.result()).get();
    }

    @CEntryPoint(name = "runDW2")
    public static CCharPointer runDW2(IsolateThread thread, CCharPointer transform, CCharPointer inputName, CCharPointer input, CCharPointer inputName2, CCharPointer input2) {
        final String script = CTypeConversion.toJavaString(transform);
        final String inputNameStr = CTypeConversion.toJavaString(inputName);
        final String inputContentStr = CTypeConversion.toJavaString(input);

        final String inputNameStr2 = CTypeConversion.toJavaString(inputName2);
        final String inputContentStr2 = CTypeConversion.toJavaString(input2);


        final WeaveResult run = runtime.run(script, new WeaveInput[]{
                new WeaveInput(inputNameStr, inputContentStr),
                new WeaveInput(inputNameStr2, inputContentStr2)
        });

        return CTypeConversion.toCString(run.result()).get();
    }

}
