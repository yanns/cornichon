package com.github.agourlay.cornichon.json

import cats.scalatest.EitherMatchers
import org.scalacheck.Gen
import org.scalatest.{ EitherValues, Matchers, WordSpec }
import com.github.agourlay.cornichon.json.JsonPathParserSpec._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class JsonPathParserSpec extends WordSpec
  with Matchers
  with ScalaCheckPropertyChecks
  with EitherValues
  with EitherMatchers {

  "JsonPathParser" when {
    "parseJsonPath" must {
      "parse JsonPath containing a field without index" in {
        forAll(fieldGen) { field ⇒
          JsonPathParser.parseJsonPath(field).right.value should be(List(FieldSelection(field)))
        }
      }

      "parse JsonPath containing a field with index" in {
        forAll(fieldGen, indexGen) { (field, index) ⇒
          JsonPathParser.parseJsonPath(s"$field[$index]").right.value should be(List(ArrayFieldSelection(field, index)))
        }
      }

      "parse JsonPath containing two fields without index" in {
        forAll(fieldGen, fieldGen) { (field1, field2) ⇒
          JsonPathParser.parseJsonPath(s"$field1.$field2").right.value should be(List(FieldSelection(field1), FieldSelection(field2)))
        }
      }

      "parse JsonPath containing a field with projection" in {
        forAll(fieldGen) { field ⇒
          JsonPathParser.parseJsonPath(s"$field[*]").right.value should be(List(ArrayFieldProjection(field)))
        }
      }

      "parse JsonPath with key containing a dot without index" in {
        forAll(fieldGen, fieldGen, fieldGen) { (field1, field2, field3) ⇒
          val composedPath = s"$field1.$field2"
          val fullPath = s"`$composedPath`.$field3"
          withClue(s"fullPath was $fullPath") {
            JsonPathParser.parseJsonPath(fullPath).right.value should be(List(FieldSelection(composedPath), FieldSelection(field3)))
          }
        }
      }

      "parse JsonPath with key containing a dot with index" in {
        forAll(fieldGen, fieldGen, fieldGen, indexGen) { (field1, field2, field3, index) ⇒
          val composedPath = s"$field1.$field2"
          val fullPath = s"`$composedPath`.$field3[$index]"
          withClue(s"fullPath was $fullPath") {
            JsonPathParser.parseJsonPath(fullPath).right.value should be(List(FieldSelection(composedPath), ArrayFieldSelection(field3, index)))
          }
        }
      }

      "return error if it starts with '.'" in {
        forAll(fieldGen) { field ⇒
          JsonPathParser.parseJsonPath(s".$field.") should be(left)
        }
      }

      "parse JsonPath containing a missing field" in {
        forAll(fieldGen) { field ⇒
          JsonPathParser.parseJsonPath(s"$field.") should be(left)
        }
      }

      "parse JsonPath containing a broken index bracket" in {
        forAll(fieldGen, indexGen) { (field, index) ⇒
          JsonPathParser.parseJsonPath(s"$field[$index[") should be(left)
        }
      }
    }
  }
}

object JsonPathParserSpec {
  val fieldGen = Gen.alphaStr.filter(_.trim.nonEmpty).filter(k ⇒ !JsonPathParser.notAllowedInField.exists(forbidden ⇒ k.contains(forbidden)))
  def fieldsGen(n: Int) = Gen.listOfN(n, fieldGen)

  val indexGen = Gen.choose(0, Int.MaxValue)
}