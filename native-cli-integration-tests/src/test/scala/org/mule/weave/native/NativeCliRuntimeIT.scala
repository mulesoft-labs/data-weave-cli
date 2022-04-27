package org.mule.weave.native

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.io.IOUtils
import org.mule.weave.v2.codegen.CodeGenerator
import org.mule.weave.v2.codegen.CodeGeneratorSettings
import org.mule.weave.v2.codegen.InfixOptions
import org.mule.weave.v2.helper.FolderBasedTest
import org.mule.weave.v2.matchers.WeaveMatchers.matchBin
import org.mule.weave.v2.matchers.WeaveMatchers.matchJson
import org.mule.weave.v2.matchers.WeaveMatchers.matchProperties
import org.mule.weave.v2.matchers.WeaveMatchers.matchString
import org.mule.weave.v2.matchers.WeaveMatchers.matchXml
import org.mule.weave.v2.parser.MappingParser
import org.mule.weave.v2.parser.ast.header.directives.ContentType
import org.mule.weave.v2.parser.ast.header.directives.DirectiveNode
import org.mule.weave.v2.parser.ast.header.directives.OutputDirective
import org.mule.weave.v2.parser.ast.structure.StringNode
import org.mule.weave.v2.sdk.ParsingContextFactory
import org.mule.weave.v2.sdk.WeaveResourceFactory
import org.scalatest.Assertion
import org.scalatest.FunSpec
import org.scalatest.Matchers

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.JarURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.zip.ZipFile
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource
import scala.collection.JavaConverters._
import scala.io.Source.fromFile
import scala.util.Try

class NativeCliRuntimeIT extends FunSpec 
  with Matchers 
  with FolderBasedTest 
  with ResourceResolver {

  private val TIMEOUT: (Int, TimeUnit) = (30, TimeUnit.SECONDS)
  private val INPUT_FILE_PATTERN = Pattern.compile( "in[0-9]+\\.[a-zA-Z]+")
  private val OUTPUT_FILE_PATTERN = Pattern.compile("out\\.[a-zA-Z]+")
  
  val testSuites =
    Seq(TestSuite("master", loadTestZipFile()))
  
  private def loadTestZipFile(): File = {
    val url = getResource("access_raw_value")
    val connection = url.openConnection.asInstanceOf[JarURLConnection]
    val zipFile = new File(connection.getJarFileURL.toURI)
    zipFile
  }
  
  testSuites.foreach {
    testSuite => {
      val wd = Files.createTempDirectory(testSuite.name).toFile
      // Unzip the jar
      if (wd.exists) {
        FileUtils.deleteDirectory(wd)
      }
      wd.mkdirs
      extractArchive(testSuite.zipFile.toPath, wd.toPath)
      runTestSuite(wd)
    }
  }
  
  private def runTestSuite(testsSuiteFolder: File): Unit = {
    val testFolders = testsSuiteFolder.listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = {
        var accept = false
        if (acceptScenario(pathname)) {
          if (pathname.isDirectory && !pathname.getName.endsWith("wip")) {
            // Ignore more than one dwl file by test case
            val dwlFiles = pathname.list((_: File, name: String) => {
              val extension = FilenameUtils.getExtension(name)
              val isInput = INPUT_FILE_PATTERN.matcher(name).matches()
              val isOutput = OUTPUT_FILE_PATTERN.matcher(name).matches()
              "dwl" ==  extension && !isInput && !isOutput
            })
            
            // We ignore java use cases for now until we resolve classpath
            // And cases with config.properties
            val javaCasesOrConfigCases = pathname.list((_: File, name: String) => {
              name.endsWith("groovy") || "config.properties" == name
            })
            
            accept = dwlFiles.length == 1 && (javaCasesOrConfigCases == null || javaCasesOrConfigCases.isEmpty)
          }
        }
        accept
      }
    })
    if (testFolders != null) {
      runTestCase(testFolders)
    }
  }
  
  def runTestCase(testFolders: Array[File]): Unit = {
    val unsortedScenarios = for {
      testFolder <- testFolders
      output <- outputFiles(testFolder)
    } yield {
      Scenario(scenarioName(testFolder, output), testFolder, inputFiles(testFolder), new File(testFolder, mainTestFile), output, configProperty(testFolder))
    }
    val scenarios = unsortedScenarios.sortBy(_.name)
    scenarios.foreach {
      scenario =>
        it(scenario.name) {
          var args = Array.empty[String]
          // Add inputs
          scenario.inputs.foreach(f => {
            val name = FilenameUtils.getBaseName(f.getName)
            args = args :+ "-i"
            args = args :+ name
            args = args :+ f.getAbsolutePath
          })

          // Add output
          val outputExtension = FilenameUtils.getExtension(scenario.output.getName)
          val outputPath = Path.of(scenario.testFolder.getPath,s"cli-out.$outputExtension")
          args = args :+ "-o"
          args = args :+ s"${outputPath.toString}"

          // Add transformation
          val weaveResource = WeaveResourceFactory.fromFile(scenario.transform)
          val parser = MappingParser.parse(MappingParser.parsingPhase(), weaveResource, ParsingContextFactory.createParsingContext())
          val documentNode = parser.getResult().astNode
          
          val headerDirectives: Seq[DirectiveNode] = documentNode.header.directives
          
          val maybeOutputDirective = headerDirectives.find(dn => dn.isInstanceOf[OutputDirective]).map(_.asInstanceOf[OutputDirective])
          
          var maybeEncoding: Option[String] = None
          var directives = headerDirectives
          if (maybeOutputDirective.isEmpty) {
            val contentType = MimeTypeMapper.getMimeTypeOf(outputExtension)
            val newOutputDirective = OutputDirective(None, Some(ContentType(contentType)), None, None)
            directives = directives :+ newOutputDirective
          } else {
            val contentType = MimeTypeMapper.getMimeTypeOf(outputExtension)
            val outputDirective = maybeOutputDirective.get
            if (outputDirective.mime.isDefined) {
              val currentContentType = outputDirective.mime.get
              val currentMime = currentContentType.mime
              if (subPart(contentType) != subPart(currentMime)) {
                val newOutputDirective = OutputDirective(None, Some(ContentType(contentType)), None, None)
                val index = directives.indexOf(outputDirective)
                directives = directives.take(index) ++ directives.drop(index + 1)
                directives = directives :+ newOutputDirective
              } else {
                maybeEncoding = getEncodingFromOutputDirective(outputDirective)
              }
            } else {
              maybeEncoding = getEncodingFromOutputDirective(outputDirective)
            }
          }

          // TODO [ML]: remove file creation
          documentNode.header.directives = directives
          val settings = CodeGeneratorSettings(InfixOptions.KEEP, alwaysInsertVersion = false, newLineBetweenFunctions = true, orderDirectives = false)
          val code = CodeGenerator.generate(documentNode, settings)
          val cliTransform = new File(scenario.testFolder, s"cli-transform-$outputExtension.dwl")
          
          try {
            Files.write(cliTransform.toPath, code.getBytes(StandardCharsets.UTF_8))
          }  catch {
            case ioe: IOException =>
              throw ioe
          }
          
          args = args :+ "-f"
          args = args :+ cliTransform.getAbsolutePath

          val (exitCode, _) = NativeCliITTestRunner(args).execute(TIMEOUT._1, TIMEOUT._2)
          
          exitCode shouldBe 0
          doAssert(outputPath.toFile, scenario.output, maybeEncoding)
        }
    }
  }
  
  private def getEncodingFromOutputDirective(outputDirective: OutputDirective): Option[String] = {
    val maybeEncodingOption = outputDirective.options.flatMap(opts => {
      opts.find(opt => {"encoding" == opt.name.name})
    })
    maybeEncodingOption.map(d => d.value.asInstanceOf[StringNode].literalValue)
  }
  
  private def extractArchive(archiveFile: Path, destPath: Path): Unit = {
    Files.createDirectories(destPath)
    val archive = new ZipFile(archiveFile.toFile)
    try {
      for (entry <- archive.entries().asScala) {
        val entryDest = destPath.resolve(entry.getName)
        if (entry.isDirectory) {
          Files.createDirectory(entryDest)
        } else {
          Files.copy(archive.getInputStream(entry), entryDest)
        }
      }
    } finally {
      if (archive != null) {
        archive.close()
      }
    }
    println(s"Extract content from: $archiveFile at $destPath")
  }
  
  private def doAssert(actualFile: File, expectedFile: File, maybeEncoding: Option[String] = None) = {
    val bytes: Array[Byte] = IOUtils.toByteArray(new FileInputStream(actualFile))
    val encoding = maybeEncoding.getOrElse("UTF-8")
    val extension = FilenameUtils.getExtension(expectedFile.getName)
    extension match {
      case "json" =>
        val actual: String = new String(bytes, encoding)
        actual should matchJson(readFile(expectedFile))
      case "xml" =>
        val actual: String = new String(bytes, encoding)
        actual should matchXml(readFile(expectedFile))
      case "dwl" =>
        val actual: String = new String(bytes, "UTF-8")
        actual should matchString(readFile(expectedFile))(after being whiteSpaceNormalised)
      case "csv" =>
        val actual: String = new String(bytes, encoding)
        actual.trim should matchString(readFile(expectedFile).trim)
      case "txt" =>
        val actual: String = new String(bytes, encoding)
        actual should matchString(readFile(expectedFile))
      case "bin" =>
        assertBinaryFile(bytes, expectedFile)
      case "urlencoded" =>
        val actual: String = new String(bytes, "UTF-8")
        actual should matchString(readFile(expectedFile).trim)
      case "properties" =>
        val actual: String = new String(bytes, "UTF-8")
        actual should matchProperties(readFile(expectedFile).trim)
      
      case "multipart" => 
        matchMultipart(expectedFile, bytes)
    }
  }

  private def assertBinaryFile(result: Array[Byte], expectedFile: File): Assertion = {
    result should matchBin(expectedFile)
  }
  
  private def matchMultipart(output: File, result: Array[Byte]) = {
    val expected = new MimeMultipart(new ByteArrayDataSource(new FileInputStream(output), "multipart/form-data"))
    val actual = new MimeMultipart(new ByteArrayDataSource(new ByteArrayInputStream(result), "multipart/form-data"))
    actual.getPreamble should matchString(expected.getPreamble)
    actual.getCount shouldBe expected.getCount

    var i = 0
    while (i < expected.getCount) {
      val expectedBodyPart = expected.getBodyPart(i)
      val actualBodyPart = actual.getBodyPart(i)
      actualBodyPart.getContentType should matchString(expectedBodyPart.getContentType)
      actualBodyPart.getDisposition should matchString(expectedBodyPart.getDisposition)
      actualBodyPart.getFileName should matchString(expectedBodyPart.getFileName)

      val actualContent = actualBodyPart.getContent
      val expectedContent = expectedBodyPart.getContent

      val actualContentString = actualContent match {
        case is: InputStream => IOUtils.toString(is, StandardCharsets.UTF_8)
        case _ => String.valueOf(actualContent);
      }

      val expectedContentString = expectedContent match {
        case is: InputStream => IOUtils.toString(is, StandardCharsets.UTF_8)
        case _ =>
          String.valueOf(expectedContent);
      }
      
      actualContentString shouldBe expectedContentString
      
      i = i + 1
    }
  }

  private def readFile(expectedFile: File): String = {
    val expectedText: String = {
      if (expectedFile.getName endsWith ".bin")
        ""
      else
        Try(fileToString(expectedFile)).getOrElse({
          val source = fromFile(expectedFile)(scala.io.Codec("UTF-16"))
          try {
            source.mkString
          } finally {
            source.close()
          }})
    }
    expectedText
  }

  private def subPart(mime: String): String = mime.split("/")(1)

  override def ignoreTests(): Array[String] = {
    // Encoding issues
    Array("binary-utf16-bom-to-json",
      "csv-invalid-utf8",
      "json_multi_encoding",
      "textplain-utf16-bom") ++
      // Unsupported charset
      Array(
        "coerciones_toString",
        "date-coercion"
      ) ++
      // Fail number formatter
      Array("coerciones_toNumber",
        "number-conversion") ++
      // DW out not includes content-type at output
      Array("dfl-inline-default-namespace",
        "dfl-inline-namespace",
        "dfl-maxCollectionSize",
        "dfl-overwrite-namespace",
        "xml-nill-multiple-attributes-nested",
        "xml-nill-multiple-attributes") ++
      // Use resources (dwl files) that is present in the Tests but not in Cli (e.g: org::mule::weave::v2::libs::)
      Array("full-qualified-name-ref",
        "import-component-alias-lib",
        "import-lib",
        "import-lib-with-alias",
        "import-named-lib",
        "import-star",
        "module-singleton",
        "multipart-write-binary",
        "read-binary-files",
        "try",
        "urlEncodeDecode") ++
      // Uses resource name that is different on Cli than in the Tests 
      Array(
        "try-recursive-call",
        "runtime_orElseTry") ++
      // Use readUrl from classpath
      Array("dw-binary",
        "read_lines") ++
      // Uses java module
      Array("java-big-decimal",
        "java-field-ref",
        "java-interop-enum",
        "java-interop-function-call",
        "write-function-with-null"
      ) ++
      // boundary configuration property is missing.
      Array("multipart-access-unknown-content-type",
        "multipart-class-cast-issue",
        "multipart-empty-body",
        "multipart-mixed-complex-scenario",
        "multipart-read-message",
        "multipart-schema-access",
        "multipart-base64-to-multipart"
      ) ++
      // Multipart Object has empty `parts` and expects at least one part
      Array("multipart-write-message", 
        "multipart-mixed-message",
        "multipart-write-subtype-override") ++
      // javax.mail.internet.ParseException - Missing start boundary
      Array("multipart_binary_nocontent_type",
        "multipart_binary_other_default_content_type") ++
      // Fail pattern match on complex object
      Array("pattern-match-complex-type") ++
      // DataFormats
      Array(
        "runtime_dataFormatsDescriptors"
      ) ++
      // UnknownContentTypeException
      Array(
        "runtime_run_coercionException",
        "runtime_run_fibo",
        "runtime_run_null_java"
      ) ++
      // Cannot coerce Null (null) to Number
      Array(
        "update-op"
      )
  }
}

case class TestSuite(name: String, zipFile: File)

case class Scenario(name: String, testFolder: File, inputs: Array[File], transform: File, output: File, configProperty: Option[File])