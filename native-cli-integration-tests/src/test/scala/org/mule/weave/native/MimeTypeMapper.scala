package org.mule.weave.native

object MimeTypeMapper {
  
  def getMimeTypeOf(extension: String): String = {
    extension match {
      case "xml" =>
        "application/xml"
      case "json" =>
        "application/json"
      case "csv" =>
        "application/csv"
      case "multipart" =>
        "multipart/form-data"
      case "dwl" =>
        "application/dw"
      case "txt" =>
        "text/plain"
      case "urlencoded" =>
        "application/x-www-form-urlencoded"
      case "bin" =>
        "application/octet-stream"
      case "properties" =>
        "text/x-java-properties"
      case "yaml" | "yml" =>
        "application/yaml"
      case "avro" =>
        "application/avro"
      case _ =>
        throw new RuntimeException("Missing mimeType for " + extension)
    }
  }
}
