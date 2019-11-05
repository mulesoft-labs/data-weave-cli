package org.mule.weave.dwnative

import org.mule.weave.v2.parser.ast.variables.NameIdentifier
import org.mule.weave.v2.resources.WeaveResourceLoader
import org.mule.weave.v2.sdk.WeaveResource
import org.mule.weave.v2.sdk.WeaveResourceResolver

object NativeResourceResolver extends WeaveResourceResolver {
  override def resolve(name: NameIdentifier): Option[WeaveResource] = {
    val resourceName = name.name
    val resource = WeaveResourceLoader.getResource(resourceName)
    Option(resource).map((resource) => WeaveResource(resourceName, resource))
  }
}
