package org.mule.weave.lib;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeLibEntryPointContractTest {

    @Test
    void allExportsDeclareExplicitExceptionHandlers() throws NoSuchMethodException {
        assertEquals(
                CEntryPointExceptionHandlers.ReturnVoid.class,
                annotation("freeCString", IsolateThread.class, CCharPointer.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnZero.class,
                annotation("createEngine", IsolateThread.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnZero.class,
                annotation("createEngineWithResolver", IsolateThread.class,
                        NativeCallbacks.ResolveModuleCallback.class, PointerBase.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnVoid.class,
                annotation("destroyEngine", IsolateThread.class, long.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnNullPointer.class,
                annotation("runScriptEngine", IsolateThread.class, long.class,
                        CCharPointer.class, CCharPointer.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnNullPointer.class,
                annotation("runScriptCallbackEngine", IsolateThread.class, long.class,
                        CCharPointer.class, CCharPointer.class,
                        NativeCallbacks.WriteCallback.class, PointerBase.class).exceptionHandler());
        assertEquals(
                CEntryPointExceptionHandlers.ReturnNullPointer.class,
                annotation("runScriptInputOutputCallbackEngine", IsolateThread.class, long.class,
                        CCharPointer.class, CCharPointer.class, CCharPointer.class,
                        CCharPointer.class, CCharPointer.class, NativeCallbacks.ReadCallback.class,
                        NativeCallbacks.WriteCallback.class, PointerBase.class).exceptionHandler());
    }

    private static CEntryPoint annotation(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return NativeLib.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(CEntryPoint.class);
    }
}
