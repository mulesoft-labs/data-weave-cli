package org.mule.weave.native

import scala.collection.mutable

object IgnoredScenarios {
  lazy val skippedTests: mutable.HashMap[String, Seq[String]] = mutable.HashMap(
    ("2.4", skipped24),
    ("2.5", skipped25)
  )
  private val skipped24 = Seq(
    "as-operator-out",
    "binary-utf16-bom-to-json-out",
    "coerciones_toBinary-out",
    "default_with_extended_null_type-out",
    "dfl-inline-default-namespace-out",
    "dfl-inline-namespace-out",
    "dfl-maxCollectionSize-out",
    "dfl-overwrite-namespace-out",
    "json_multi_encoding-out",
    "logical-and-out",
    "logical-or-out",
    "multipart-base64-to-multipart-out",
    "runtime_run_unhandled_compilation_exception-out",
    "sql_date_mapping-out",
    "textplain-utf16-bom-out",
    "type-equality-out",
    "xml-nill-multiple-attributes-nested-out",
    "xml-nill-multiple-attributes-out",
    "read_scalar_values-out"
  )

  private val skipped25 = Seq(
    "binary-utf16-bom-to-json-out",
    "json_multi_encoding-out",
    "textplain-utf16-bom-out"
  )
}
