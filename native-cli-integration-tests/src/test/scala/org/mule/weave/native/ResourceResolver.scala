package org.mule.weave.native

import java.net.URL

trait ResourceResolver {

  def getResource(resource: String): URL = {
    getClass.getClassLoader.getResource(resource)
  }
  
  def getResourcePath(resource: String): String = {
    getResource(resource).getPath
  }
}
