package org.mule.weave.dwnative.dependencies

sealed trait DependencyModel

case class MavenDependencyModel(artifactId: String, groupId: String, version: String, repository: Array[MavenRepositoryModel]) extends DependencyModel {
  def fullArtifactID(): String = s"${artifactId}:${groupId}:${version}"

}

case class MavenRepositoryModel(url: String, credentials: Option[MavenCredentialsModel])

case class MavenCredentialsModel(username: String, password: String)
