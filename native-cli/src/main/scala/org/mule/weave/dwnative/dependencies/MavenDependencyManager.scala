package org.mule.weave.dwnative.dependencies

import coursier.Dependency
import coursier.LocalRepositories.Dangerous
import coursier.MavenRepository
import coursier.Module
import coursier.ModuleName
import coursier.Organization
import coursier.Repositories
import coursier.Resolution
import coursier.ResolutionProcess
import coursier._
import coursier.cache.Cache
import coursier.cache.CacheLogger
import coursier.cache.FileCache
import coursier.util.Gather
import coursier.util.Task
import org.mule.weave.dwnative.cli.Console

import java.io.File
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.concurrent.duration._

class MavenDependencyManager(
                              console: Console,
                              cacheDirectory: File,
                              executor: ExecutorService, //The executor service to be used for resolving the dependencies in parallel
                            ) extends DependencyManager {


  val context: ExecutionContextExecutor = ExecutionContext.fromExecutor(executor)

  override def retrieve(dep: DependencyModel): DependencyResolutionResult = {


    val cache: Cache[Task] = FileCache()
      .withLocation(cacheDirectory)
      .withLogger(new CacheLogger {
        override def downloadingArtifact(url: String): Unit = {
          console.info(s"Downloading: `${url}`.")
        }
      })
      .withPool(executor)
      .withTtl(1.day)
      .noCredentials

    val mdm = dep match {
      case mdm: MavenDependencyModel => {
        mdm
      }
      case _ => throw new RuntimeException(s"Unsupported dependency model `${dep.getClass}`.")
    }

    val repositories: Seq[MavenRepository] = if (mdm.repository.isEmpty) {
      Seq(
        MavenRepository("https://maven.anypoint.mulesoft.com/api/v3/maven"),
        MavenRepository("https://repository.mulesoft.org/nexus/content/repositories/releases/"),
        MavenRepository("https://repository.mulesoft.org/nexus/content/repositories/snapshots/", changing = Some(true)),
        Repositories.central)
    } else {
      mdm.repository.map((repository) => {
        MavenRepository(repository.url)
      }).toSeq
    }

    val moduleName = Module(Organization(mdm.groupId), ModuleName(mdm.artifactId))
    val files: Future[Seq[File]] = Fetch(cache)
      .addRepositories(repositories: _*)
      .addDependencies(Dependency(moduleName, mdm.version, attributes = Attributes(classifier = Classifier("dw-library"))))
      .future()

    DependencyResolutionResult(mdm.fullArtifactID(), kind, files)
  }

  override def kind: String = {
    "maven"
  }

  override def support(dep: DependencyModel): Boolean = dep.isInstanceOf[MavenDependencyModel]
}


