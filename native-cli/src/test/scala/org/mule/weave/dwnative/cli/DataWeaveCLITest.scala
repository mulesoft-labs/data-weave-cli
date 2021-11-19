package org.mule.weave.dwnative.cli

import org.mule.weave.dwnative.utils.DataWeaveUtils
import org.scalatest.FreeSpec
import org.scalatest.Matchers

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import scala.io.Source

class DataWeaveCLITest extends FreeSpec with Matchers {

  "should work with output application/json" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("output application/json --- (1 to 3)[0]"), new TestConsole(System.in, stream))
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    result.trim shouldBe "1"
  }


  "should take into account the env variable for default output" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("root: 'Mariano'"), new TestConsole(System.in, stream, Map(DataWeaveUtils.DW_DEFAULT_OUTPUT_MIMETYPE_VAR -> "application/xml")))
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result: String = source.mkString
    val expected =
      """<?xml version='1.0' encoding='UTF-8'?>
        |<root>Mariano</root>""".stripMargin
    result.trim shouldBe expected
  }


  "should work with simple script and not output" in {
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("(1 to 3)[0]"), new TestConsole(System.in, stream))
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString
    result.trim shouldBe "1"
  }

  "should work ok when sending payload from stdin" in {
    val input: String =
      """[
        |  1,
        |  2,
        |  3
        |]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("payload[0]"), new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream))
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1"
  }

  "should use var" in {
    val input: String =
      """[
        |  1,
        |  2,
        |  3
        |]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    new DataWeaveCLIRunner().run(Array("payload[0]"), new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream))
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1"
  }

  "should work with light formats" in {
    val input: String =
      """[{
        |  "a" : 1,
        |  "b" : 2,
        |  "c" : 3
        |}]
        """.stripMargin.trim
    val stream = new ByteArrayOutputStream()
    val testConsole = new TestConsole(new ByteArrayInputStream(input.getBytes("UTF-8")), stream)
    new DataWeaveCLIRunner().run(Array("input payload json output csv header=false ---payload"), testConsole)
    val source = Source.fromBytes(stream.toByteArray, "UTF-8")
    val result = source.mkString.trim
    source.close()
    result.trim shouldBe "1,2,3"
  }


}
