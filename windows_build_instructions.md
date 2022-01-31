
# Step 1

gradlew native-cli:shadowJar

# Step 2

Run this script inside *x64 Native Tools Command*

```bash
C:\Users\mulesoft\.gradle\caches\com.palantir.graal\21.3.0\11\graalvm-ce-java11-21.3.0\bin\native-image.cmd -H:+ReportExceptionStackTraces -J-Xmx4G --no-fallback --allow-incomplete-classpath -H:EnableURLProtocols=http,https --initialize-at-run-time=io.netty,org.asynchttpclient,org.mule.weave.v2.module.http.netty.HttpAsyncClientService,scala.util.Random,org.mule.weave.v2.sdk.SPIBasedModuleLoaderProvider$ --initialize-at-build-time=sun.instrument.InstrumentationImpl,org.mule.weave.dwnative.initializer.Startup,org.mule.weave.dwnative.,org.mule.weave.v2.parser.,org.mule.weave.v2.codegen.StringCodeWriter,org.mule.weave.v2.resources.WeaveResourceLoader$,org.mule.weave.v2.annotations.,org.mule.weave.v2.ts.,org.mule.weave.v2.scope.,org.mule.weave.v2.scope.,org.mule.weave.v2.grammar.,org.mule.weave.v2.sdk.,org.mule.weave.v2.utils.,org.mule.weave.v2.versioncheck.,scala.,org.parboiled2.,shapeless.syntax. -cp native-cli-100.100.100-all.jar "org.mule.weave.dwnative.cli.DataWeaveCLI"  
```