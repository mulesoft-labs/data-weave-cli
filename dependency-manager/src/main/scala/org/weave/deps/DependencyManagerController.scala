package org.weave.deps


import java.io.File

import scala.concurrent.Future

trait DependencyManagerController {
  /**
    * If return true it should download the given artifact
    *
    * @param id   the artifact id
    * @param kind the artifact kind i.e maven, resource
    * @return true to download false to ignore
    */
  def shouldDownload(id: String, kind: String): Boolean = true

  /**
    * Callback for the downloaded artifact
    *
    * @param id       the id
    * @param kind     the kind
    * @param artifact a future to download the file
    */
  def downloaded(id: String, kind: String, artifact: Future[Seq[Artifact]]): Unit

}

case class Artifact(file: File, isDirectory: Boolean = false)
