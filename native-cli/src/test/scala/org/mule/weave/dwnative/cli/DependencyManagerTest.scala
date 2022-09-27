package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.dependencies.ResolutionErrorHandler
import org.mule.weave.dwnative.dependencies.DependencyModel
import org.mule.weave.dwnative.dependencies.DependencyResolutionResult
import org.mule.weave.dwnative.dependencies.MavenDependencyModel
import org.mule.weave.dwnative.dependencies.SpellDependencyManager
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.File

class DependencyManagerTest extends FreeSpec with Matchers {

  "it should parse the build definition correctly" in {
    val simpleSpellWithDependencies = new File(TestUtils.getSpellsFolder(), "SimpleSpellWithDependencies")
    val dependencies = new File(simpleSpellWithDependencies, "dependencies.dwl")

    val testConsole = new TestConsole()
    val manager = new SpellDependencyManager(simpleSpellWithDependencies, testConsole)
    val nativeRuntime = new NativeRuntime(TestUtils.getMyLocalSpellWithLib, Array.empty, testConsole)
    val deps: Array[_ <: DependencyModel] = manager.collectDependencies(dependencies, nativeRuntime)
    assert(deps.length == 1)
    deps(0) match {
      case MavenDependencyModel(artifactId, groupId, version, repository) => {
        assert(artifactId == "data-weave-analytics-library")
        assert(groupId == "68ef9520-24e9-4cf2-b2f5-620025690913")
        assert(version == "1.0.1")
        assert(repository.length == 1)
        assert(repository(0).url == "https://maven.anypoint.mulesoft.com/api/v3/maven")
      }
      case _ => fail("Expecting maven model")
    }
  }


  "it should resolve the artifacts correctly" in {
    val simpleSpellWithDependencies = new File(TestUtils.getSpellsFolder(), "SimpleSpellWithDependencies")
    val testConsole = DefaultConsole
    val manager = new SpellDependencyManager(simpleSpellWithDependencies, testConsole)
    val nativeRuntime = new NativeRuntime(TestUtils.getMyLocalSpellWithLib, Array.empty, testConsole)
    val results: Array[DependencyResolutionResult] = manager.resolveDependencies(nativeRuntime)
    assert(results.length == 1)
    val artifacts = results.flatMap((a) => {
      a.resolve(new ResolutionErrorHandler {
        override def onError(id: String, message: String): Unit = {
          fail(s"${id} : ${message}")
        }
      })
    })
    assert(!artifacts.isEmpty)
  }

}
