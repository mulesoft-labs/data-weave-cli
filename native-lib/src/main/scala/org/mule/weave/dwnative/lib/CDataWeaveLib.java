/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.mule.weave.dwnative.lib;

import com.oracle.svm.core.c.ProjectHeaderFile;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.AllowNarrowingCast;
import org.graalvm.nativeimage.c.struct.AllowWideningCast;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;


@CContext(CDataWeaveLib.CDataWeaveLibDirectives.class)
public class CDataWeaveLib {

    static class CDataWeaveLibDirectives implements CContext.Directives {

        @Override
        public List<String> getHeaderFiles() {
            /*
             * The header file with the C declarations that are imported. We use a helper class that
             * locates the file in our project structure.
             */
            return Collections.singletonList(ProjectHeaderFile.resolve("org.mule.weave.dwnative.lib", "dw_compiler.h"));
        }
    }


    /* Import a C structure, with accessor methods for every field. */
    @CStruct("compilation_result")
    interface CompilationResult extends PointerBase {

        /* Read access of a field. A call to the function is replaced with a raw memory load. */
        @CField("f_success")
        @AllowNarrowingCast
        boolean isSuccess();

        /* Write access of a field. A call to the function is replaced with a raw memory store. */
        @CField("f_success")
        @AllowWideningCast
        void setSuccess(boolean value);

        @CField("f_pel")
        void setPeregrineExpression(CCharPointer expression);

        @CField("f_pel")
        CCharPointer getPeregrineExpression();


        @CField("f_error_message")
        void setErrorMessage(CCharPointer errorMessage);

    }

    /* Java function that can be called directly from C code. */
    @CEntryPoint(name = "compile")
    public static void compile(@SuppressWarnings("unused") IsolateThread thread, CCharPointer transform, CompilationResult copy) {
        final String script = CTypeConversion.toJavaString(transform);
        /* Allocate a C structure in our stack frame. */
        //maloc(sizeOf(compilation_result))
        PeregrineCompiler peregrineCompiler = new PeregrineCompiler();
        PeregrineCompilationResult compilationResult = peregrineCompiler.compile(script);
        if (compilationResult instanceof SuccessPeregrineCompilationResult) {
            copy.setSuccess(true);
            String expression = ((SuccessPeregrineCompilationResult) compilationResult).pelExpression();
            int length = 1000000;
            UnsignedWord unsignedWord = WordFactory.unsigned(length);
            CCharPointer malloc = UnmanagedMemory.malloc(unsignedWord);
            CTypeConversion.toCString(expression, malloc, unsignedWord);
            copy.setPeregrineExpression(malloc);
        } else if (compilationResult instanceof FailurePeregrineCompilationResult) {
            copy.setSuccess(false);
            String reason = ((FailurePeregrineCompilationResult) compilationResult).reason();
            copy.setErrorMessage(CTypeConversion.toCString(reason).get());
        }
    }


    @CEntryPoint(name = "freeJavaObject")
    public static void freeJavaObject(@SuppressWarnings("unused") IsolateThread thread, CompilationResult transform) {
        UnmanagedMemory.free(transform.getPeregrineExpression());
    }

}