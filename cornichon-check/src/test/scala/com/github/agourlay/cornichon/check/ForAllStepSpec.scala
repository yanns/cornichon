package com.github.agourlay.cornichon.check

import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.dsl.ProvidedInstances
import com.github.agourlay.cornichon.steps.cats.EffectStep
import com.github.agourlay.cornichon.steps.regular.assertStep.{ AssertStep, GenericEqualityAssertion }
import com.github.agourlay.cornichon.steps.wrapped.AttachStep
import utest._

object ForAllStepSpec extends TestSuite with ProvidedInstances with CheckDsl with CheckStepUtil {

  val tests = Tests {
    test("validate invariant - correct") {
      val maxRun = 10

      val forAllStep = for_all("double reverse", maxNumberOfRuns = maxRun, stringGen) { s =>
        AssertStep("double reverse string", _ => GenericEqualityAssertion(s, s.reverse.reverse))
      }
      val s = Scenario("scenario with forAllStep", forAllStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      assert(res.isSuccess)
    }

    test("validate invariant - incorrect") {
      val maxRun = 10
      var uglyCounter = 0
      val incrementEffect: Step = EffectStep.fromSync("identity", sc => { uglyCounter = uglyCounter + 1; sc.session })

      val forAllStep = for_all("weird case", maxNumberOfRuns = maxRun, integerGen) { _ =>
        val assert = if (uglyCounter < 5) alwaysValidAssertStep else brokenEffect
        AttachStep(_ => incrementEffect :: assert :: Nil)
      }
      val s = Scenario("scenario with forAllStep", forAllStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(uglyCounter == 6)
          assert(f.msg == """Scenario 'scenario with forAllStep' failed:
                            |
                            |at step:
                            |always boom
                            |
                            |with error(s):
                            |boom!
                            |
                            |seed for the run was '1'
                            |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("always terminates - with maxNumberOfRuns") {
      val maxRun = 100
      var uglyCounter = 0
      val incrementEffect: Step = EffectStep.fromSync("identity", sc => {
        uglyCounter = uglyCounter + 1
        sc.session
      })

      val forAllStep = for_all("fails", maxNumberOfRuns = maxRun, integerGen)(_ => incrementEffect)
      val s = Scenario("scenario with forAllStep", forAllStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: SuccessScenarioReport =>
          assert(f.isSuccess)
          assert(uglyCounter == maxRun)

        case _ =>
          assertMatch(res) { case _: SuccessScenarioReport => }
      }
    }

    test("report failure when a nested step explodes") {
      val forAllStep = for_all("fails", maxNumberOfRuns = 10, integerGen)(_ => brokenEffect)
      val s = Scenario("scenario with forAllStep", forAllStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg ==
            """Scenario 'scenario with forAllStep' failed:
                |
                |at step:
                |always boom
                |
                |with error(s):
                |boom!
                |
                |seed for the run was '1'
                |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("fail the test if the Gen throws") {
      val forAllStep = for_all("fails", maxNumberOfRuns = 10, brokenIntGen)(_ => neverValidAssertStep)
      val s = Scenario("scenario with forAllStep", forAllStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }
  }
}
