package org.mule.weave.dwnative.dependencies

import java.io.File
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
  * Dependency manager will resolve an artifact with from a given ID.
  */
trait DependencyManager {
  /**
    * Returns true if this dependency manager support the given model
    *
    * @param dep the dependency
    * @return True if it is supported else false
    */
  def support(dep: DependencyModel): Boolean

  /**
    * Retrieves the given artifact
    *
    * @param artifactId       The artifactId to retrieve
    * @param controller       The callback to be used to resolving an artifact
    * @param messageCollector This message collector
    */
  def retrieve(artifactId: DependencyModel): DependencyResolutionResult

  /**
    * Returns the kind of dependencies it handles i.e maven
    *
    * @return The kind of dependencies it handles
    */
  def kind: String
}

case class DependencyResolutionResult(id: String, kind: String, artifact: Future[Seq[File]]) {
  def resolve(messageCollector: DependencyManagerMessageCollector): Array[File] = {
    try {
      val files = Await.result(artifact, Duration.Inf)
      files.toArray
    } catch {
      case e: Exception => {
        messageCollector.onError(s"${id}", e.getMessage)
        Array.empty
      }
    }
  }
}


/**
  * Callback that handles all messages when resolving an artifact
  */
trait DependencyManagerMessageCollector {

  /**
    * When an error occurred when trying to resolve an artifact id
    *
    * @param id      The id of the artifact
    * @param message The error message
    */
  def onError(id: String, message: String): Unit
}

