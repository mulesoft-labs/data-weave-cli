package org.mule.weave.dwnative.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

import org.scalatest.FreeSpec
import org.scalatest.Matchers

import scala.io.Source

class DataWeaveCLITest extends FreeSpec with Matchers {

  "should work with output application/json" in {
    val out = System.out
    try {
      val stream = new ByteArrayOutputStream()
      System.setOut(new PrintStream(stream, true))
      new DataWeaveCLIRunner().run(Array("output application/json --- (1 to 3)[0]"))
      val source = Source.fromBytes(stream.toByteArray, "UTF-8")
      val result = source.mkString
      result.trim shouldBe "1"
    } finally {
      System.setOut(out)
      println("Finish OK 3")
    }
  }

  "should work with simple script and not output" in {
    val defaultOut = System.out
    try {
      val stream = new ByteArrayOutputStream()
      System.setOut(new PrintStream(stream, true))
      new DataWeaveCLIRunner().run(Array("(1 to 3)[0]"))
      val source = Source.fromBytes(stream.toByteArray, "UTF-8")
      val result = source.mkString
      result.trim shouldBe "1"
    } finally {
      System.setOut(defaultOut)
    }
  }

  "should work ok when sending payload from stdin" in {
    val out = System.out
    val in = System.in
    try {
      val input =
        """[
          |  1,
          |  2,
          |  3
          |]
        """.stripMargin.trim
      val stream = new ByteArrayOutputStream()
      System.setOut(new PrintStream(stream, true))
      System.setIn(new ByteArrayInputStream(input.getBytes("UTF-8")))
      new DataWeaveCLIRunner().run(Array("payload[0]"))
      val source = Source.fromBytes(stream.toByteArray, "UTF-8")
      val result = source.mkString.trim
      source.close()
      result.trim shouldBe "1"
    } finally {
      System.setOut(out)
      System.setIn(in)
      println("Finish OK 2")
    }
  }

  "should work with light formats" in {
    val out = System.out
    val in = System.in
    try {
      val input =
        """[{
          |  "a" : 1,
          |  "b" : 2,
          |  "c" : 3
          |}]
        """.stripMargin.trim
      val stream = new ByteArrayOutputStream()
      System.setOut(new PrintStream(stream, true))
      System.setIn(new ByteArrayInputStream(input.getBytes("UTF-8")))
      new DataWeaveCLIRunner().run(Array("input payload json output csv header=false ---payload"))
      val source = Source.fromBytes(stream.toByteArray, "UTF-8")
      val result = source.mkString.trim
      source.close()
      result.trim shouldBe "1,2,3"
    } finally {
      System.setOut(out)
      System.setIn(in)
      println("Finish OK 2")
    }
  }



}
