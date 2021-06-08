package org.mule.weave.dwnative.lib;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.mule.weave.v2.parser.MappingParser;
import org.mule.weave.v2.parser.ast.structure.DocumentNode;
import org.mule.weave.v2.parser.phase.ParsingResult;
import org.mule.weave.v2.parser.phase.PhaseResult;
import org.mule.weave.v2.sdk.ParsingContextFactory;
import org.mule.weave.v2.sdk.WeaveResource;


public class DataWeaveNativeLibrary {

    @CEntryPoint(name = "compilePeregrineExpression")
    public static CCharPointer compilePeregrineExpression(IsolateThread thread, CCharPointer transform) {
        final String script = CTypeConversion.toJavaString(transform);
        final PhaseResult<ParsingResult<DocumentNode>> parse = MappingParser.parse(MappingParser.parsingPhase(), WeaveResource.anonymous(script), ParsingContextFactory.createParsingContext());
        return CTypeConversion.toCString("{ \"hasErrors\": " + parse.hasErrors() + "}").get();
    }
}
