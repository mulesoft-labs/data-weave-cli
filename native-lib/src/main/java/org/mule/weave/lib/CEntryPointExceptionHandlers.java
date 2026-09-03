package org.mule.weave.lib;

import com.oracle.svm.core.Uninterruptible;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

final class CEntryPointExceptionHandlers {
    private CEntryPointExceptionHandlers() {
    }

    static final class ReturnZero implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Return an ABI sentinel after an entrypoint exception")
        static long handle(Throwable ignored) {
            return 0L;
        }
    }

    static final class ReturnNullPointer implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Return an ABI sentinel after an entrypoint exception")
        static CCharPointer handle(Throwable ignored) {
            return WordFactory.nullPointer();
        }
    }

    static final class ReturnVoid implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Contain an exception at the C ABI boundary")
        static void handle(Throwable ignored) {
        }
    }
}
