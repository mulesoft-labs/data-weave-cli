package org.weave.deps

import java.io.File
import java.util.concurrent.ExecutorService

import coursier.LocalRepositories.Dangerous
import coursier.MavenRepository
import coursier._
import coursier.cache.Cache
import coursier.cache.CacheLogger
import coursier.cache.FileCache
import coursier.util.Gather
import coursier.util.Task
import org.mule.weave.v2.parser.DefaultMessage
import org.mule.weave.v2.parser.MessageCollector
import org.mule.weave.v2.parser.ParsingPhaseCategory
import org.mule.weave.v2.parser.ast.AstNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNode
import org.mule.weave.v2.parser.ast.annotation.AnnotationNodeHelper
import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.parser.phase.AnnotationProcessor
import org.mule.weave.v2.scope.AstNavigator
import org.mule.weave.v2.scope.ScopesNavigator

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class MavenDependencyAnnotationProcessor(cacheDirectory: File, controller: DependencyManagerController, executor: ExecutorService, logger: CacheLogger = CacheLogger.nop) extends AnnotationProcessor {

  private val cache: Cache[Task] = FileCache()
    .withLocation(cacheDirectory)
    .withLogger(logger)
    .withPool(executor)
    .withTtl(1.day)
    .noCredentials

  val context: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  private val repositories: Seq[MavenRepository] = loadRepos()

  private def loadRepos(): Seq[MavenRepository] = {
    Seq(
      Dangerous.maven2Local,
      MavenRepository("http://repository.mulesoft.org/nexus/content/repositories/snapshots/", changing = Some(true)),
      MavenRepository("http://repository.mulesoft.org/nexus/content/repositories/releases/"),
      MavenRepository("https://repo1.maven.org/maven2"),
      Repositories.jitpack,
      Repositories.central,
      Repositories.sonatype("releases")
    )
  }

  /**
    * Run the annotation processing logic on the scope navigation phase
    */
  override def run(annotatedNode: AstNode, astNavigator: AstNavigator, scopeNavigator: ScopesNavigator, messageCollector: MessageCollector, annotation: AnnotationNode): Unit = {
    val maybeArtifactId: Option[String] = AnnotationNodeHelper.argString("artifactId", annotation)
    maybeArtifactId match {
      case Some(artifactId) => {
        if (!controller.shouldDownload(artifactId, "maven")) {
          return //return if we should not download it
        }
        retrieve(artifactId, (message) => {
          messageCollector.error(new DefaultMessage(message, ParsingPhaseCategory), annotatedNode.location())
        })
      }
      case None => {
        //Nothing here the annotation will be validated
      }
    }
  }

  def retrieve(artifactId: String, messageCollector: (String) => Unit): Unit = {
    val parts: Array[String] = artifactId.split(':')
    val start: Resolution = Resolution(Seq(Dependency(Module(Organization(parts(0)), ModuleName(parts(1))), parts(2))))
    val fetch: ResolutionProcess.Fetch[Task] = ResolutionProcess.fetch(repositories, cache.fetch)
    val resolution: Resolution = start.process.run(fetch).unsafeRun()(context)
    val artifact =
      Gather[Task]
        .gather(resolution.artifacts().map((classifier) => {
          cache.file(classifier).run
        }))
        .future()(context)
        .map((downloads) => {
          downloads.headOption match {
            case Some(head) => {
              head match {
                case Left(ae) => {
                  //Show that we are not able to download it
                  messageCollector(ae.message)
                  None
                }
                case Right(file) => {
                  Some(Artifact(file))
                }
              }
            }
            case None => {
              //Show that we are not able to download it
              messageCollector(s"Unable to resolve ${artifactId}")
              None
            }
          }
        })(context)
    controller.downloaded(artifactId, "maven", artifact)
  }
}


object MavenDependencyAnnotationProcessor {

  def apply(cacheDirectory: File, controller: DependencyManagerController, executor: ExecutorService): MavenDependencyAnnotationProcessor = {
    new MavenDependencyAnnotationProcessor(cacheDirectory, controller, executor)
  }

  val ANNOTATION_NAME: NameIdentifier = NameIdentifier("dw::deps::Deps::MavenDependency")
}
