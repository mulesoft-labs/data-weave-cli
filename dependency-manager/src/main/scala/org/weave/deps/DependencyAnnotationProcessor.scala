package org.weave.deps
import java.io.File
import java.nio.file.Files

import coursier.MavenRepository
import coursier._
import coursier.cache.ArtifactError
import coursier.cache.Cache
import coursier.core.Authentication
import coursier.util.Gather
import coursier.util.Task
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.LiteralValueAstNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNode
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.AnnotationProcessor
import org.mule.weave.v2.scope.AstNavigator
import org.mule.weave.v2.scope.ScopesNavigator

import scala.concurrent.ExecutionContext.Implicits.global

class DependencyAnnotationProcessor(targetDirectory: File) extends AnnotationProcessor {

  /**
    * Run the annotation processing logic on the scope navigation phase
    */
  override def run(annotatedNode: AstNode, astNavigator: AstNavigator, scopeNavigator: ScopesNavigator, messageCollector: MessageCollector, annotation: AnnotationNode): Unit = {
    val homeUser = new File(System.getProperty("user.home"))
    val repositories = Seq(
      MavenRepository(new File(homeUser, ".m2/repository").getAbsolutePath),
      MavenRepository("http://repository.mulesoft.org/nexus/content/repositories/snapshots/", changing = Some(true)),
      MavenRepository(
        "https://maven.anypoint.mulesoft.com/api/v1/organizations/142ebe1a-6e25-4670-9220-0cdce791f1b8/maven",
        authentication = Some(Authentication("machaval", "3lg0rd0FC"))),
      MavenRepository("https://repo1.maven.org/maven2"))
    val parts = annotation.args.get.args.head.value.asInstanceOf[LiteralValueAstNode].literalValue.split(':')
    println("Trying to Downloading: " + parts)
    val start = Resolution(Seq(Dependency(Module(Organization(parts(0)), ModuleName(parts(1))), parts(2))))
    val fetch = ResolutionProcess.fetch(repositories, Cache.default.fetch)
    val resolution = start.process.run(fetch).unsafeRun()
    val localArtifacts: Either[ArtifactError, File] =
      Gather[Task]
        .gather(resolution.artifacts().map(Cache.default.file(_).run))
        .unsafeRun()
        .head

    localArtifacts match {
      case Left(ae) => {
        println(ae.message)
      }
      case Right(file) => {
        Files.move(file.toPath, targetDirectory.toPath)
      }
    }
  }
}

object DependencyAnnotationProcessor {
  val ANNOTATION_NAME = NameIdentifier("dw::deps::Deps::Dependency")
}
