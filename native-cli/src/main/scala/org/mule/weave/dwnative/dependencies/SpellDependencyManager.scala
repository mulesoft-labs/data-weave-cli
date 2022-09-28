package org.mule.weave.dwnative.dependencies

import org.mule.weave.dwnative.NativeRuntime
import org.mule.weave.dwnative.cli.Console
import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.mule.weave.v2.runtime.ArrayDataWeaveValue
import org.mule.weave.v2.runtime.DataWeaveValue
import org.mule.weave.v2.runtime.ObjectDataWeaveValue
import org.mule.weave.v2.runtime.ScriptingBindings
import org.mule.weave.v2.runtime.SimpleDataWeaveValue

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import scala.io.Source

class SpellDependencyManager(projectHome: File, console: Console) {

  private val CACHE_FOLDER: File = new DataWeaveUtils(console).getCacheHome()
  private val DEPENDENCY_MANAGER = Array(new MavenDependencyManager(console, CACHE_FOLDER, Executors.newCachedThreadPool()))

  def asRepository(v: DataWeaveValue): MavenRepositoryModel = {
    v match {
      case value: ObjectDataWeaveValue => {
        val url = selectRequiredStringValue(value, "url")
        MavenRepositoryModel(url, None)
      }
      case _ => throw new RuntimeException(s"Expecting `Object` but got `${v.typeName()}`")
    }
  }

  def asDependency(value: ObjectDataWeaveValue): DependencyModel = {
    val kind = selectRequiredStringValue(value, "kind")
    kind match {
      case s if s equalsIgnoreCase "maven" => {
        val artifactId = selectRequiredStringValue(value, "artifactId")
        val groupId = selectRequiredStringValue(value, "groupId")
        val version = selectRequiredStringValue(value, "version")
        val repositories = selectArrayValue(value, "repositories")
        val repositoriesDef: Array[MavenRepositoryModel] = repositories.map((v) => v.map((v) => asRepository(v))).getOrElse(Array())
        MavenDependencyModel(artifactId, groupId, version, repositoriesDef)
      }
      case _ => throw new RuntimeException(s"Invalid kind field `${kind}`.")
    }
  }

  def resolveDep(dep: DependencyModel, console: Console): DependencyResolutionResult = {
    DEPENDENCY_MANAGER
      .find((dm) => {
        dm.support(dep)
      })
      .map((dm) => {
        dm.retrieve(dep)
      })
      .getOrElse(throw new RuntimeException(s"Unable to find support for: `${dep.getClass}`."))
  }

  def resolveDependencies(nr: NativeRuntime): Array[DependencyResolutionResult] = {
    val dependenciesFile = new File(projectHome, "dependencies.dwl")
    if (dependenciesFile.exists()) {
      val dependencies: Array[_ <: DependencyModel] = collectDependencies(dependenciesFile, nr)
      dependencies
        .map((dep) => {
          resolveDep(dep, console)
        })
    } else {
      Array.empty
    }
  }

  def collectDependencies(file: File, runtime: NativeRuntime): Array[_ <: DependencyModel] = {
    val source = Source.fromInputStream(new FileInputStream(file), "UTF-8")
    try {
      val scriptContent = source.mkString
      val executeResult = runtime.eval(scriptContent, ScriptingBindings(), file.getName, None)
      executeResult.asDWValue() match {
        case value: ObjectDataWeaveValue => {
          selectValue(value, "dependencies")
            .map {
              case value: ArrayDataWeaveValue => {
                value.elements().map {
                  case value: ObjectDataWeaveValue => {
                    asDependency(value)
                  }
                  case _ => throw new RuntimeException("Expecting dependency")
                }
              }
              case value: ObjectDataWeaveValue => {
                Array(asDependency(value))
              }
              case _ => {
                throw new RuntimeException("Expecting Array of Dependencies")
              }
            }.getOrElse(Array())
        }
        case value: SimpleDataWeaveValue if (value.value() == null) => {
          Array()
        }
        case _ => {
          throw new RuntimeException("Invalid dependencies.dwl data structure.")
        }

      }
    } finally {
      source.close()
    }
  }

  private def selectValue(value: ObjectDataWeaveValue, fieldName: String) = {
    value.entries()
      .find((dw) => {
        dw.name.name.equals(fieldName)
      }).map(_.value)
  }

  private def selectArrayValue(value: ObjectDataWeaveValue, fieldName: String) = {
    selectValue(value, fieldName).map {
      case value: ArrayDataWeaveValue => {
        value.elements()
      }
      case v => throw new RuntimeException(s"Expecting `${fieldName}` to be `Array` but was `${v.typeName()}``")
    }
  }

  private def selectRequiredArrayValue(value: ObjectDataWeaveValue, fieldName: String): Array[DataWeaveValue] = {
    selectArrayValue(value, fieldName).getOrElse(throw new RuntimeException(s"Missing required `Array` field `${fieldName}`"))
  }

  private def selectStringValue(value: ObjectDataWeaveValue, fieldName: String): Option[String] = {
    selectValue(value, fieldName).map {
      case value: SimpleDataWeaveValue => {
        value.value().toString
      }
      case v => throw new RuntimeException(s"Expecting `${fieldName}` to be `String` but was `${v.typeName()}`")
    }
  }

  private def selectRequiredStringValue(value: ObjectDataWeaveValue, fieldName: String) = {
    selectStringValue(value, fieldName)
      .getOrElse(throw new RuntimeException(s"Missing required `String` field `${fieldName}``"))
  }
}
