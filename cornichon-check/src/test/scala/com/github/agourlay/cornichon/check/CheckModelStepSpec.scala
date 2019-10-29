package com.github.agourlay.cornichon.check

import com.github.agourlay.cornichon.check.checkModel._
import com.github.agourlay.cornichon.core._
import com.github.agourlay.cornichon.dsl.ProvidedInstances
import com.github.agourlay.cornichon.steps.cats.EffectStep
import utest._

object CheckModelStepSpec extends TestSuite with ProvidedInstances with CheckStepUtil {

  def dummyProperty1(name: String, preNeverValid: Boolean = false, step: Step = identityStep, callGen: Boolean = false): PropertyN[Int, NoValue, NoValue, NoValue, NoValue, NoValue] =
    Property1(
      description = name,
      preCondition = if (preNeverValid) neverValidAssertStep else identityStep,
      invariant = g => if (callGen) { g(); step } else step)

  val tests = Tests {
    test("detect empty transition for starting property") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property")
      val transitions = Map(otherAction -> ((100, starting) :: Nil))
      val model = Model("model with empty transition for starting property", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg == """Scenario 'scenario with checkStep' failed:
                            |
                            |at step:
                            |Checking model 'model with empty transition for starting property' with maxNumberOfRuns=10 and maxNumberOfTransitions=10
                            |
                            |with error(s):
                            |No outgoing transitions definition found for starting property 'starting property'
                            |
                            |seed for the run was '1'
                            |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("detect duplicate transition to target") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property")
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((80, starting) :: (20, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg == """Scenario 'scenario with checkStep' failed:
                            |
                            |at step:
                            |Checking model 'model with empty transition for starting' with maxNumberOfRuns=10 and maxNumberOfTransitions=10
                            |
                            |with error(s):
                            |Transitions definition from 'other property' contains duplicates target properties
                            |
                            |seed for the run was '1'
                            |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("detect incorrect weigh definition") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property")
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((101, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg == """Scenario 'scenario with checkStep' failed:
                            |
                            |at step:
                            |Checking model 'model with empty transition for starting' with maxNumberOfRuns=10 and maxNumberOfTransitions=10
                            |
                            |with error(s):
                            |Transitions definition from 'other property' contains incorrect weight definition (above 100)
                            |
                            |seed for the run was '1'
                            |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("always terminates with maxNumberOfRuns") {
      val maxRun = 100
      var uglyCounter = 0
      val incrementEffect: Step = EffectStep.fromSync("identity", sc => { uglyCounter = uglyCounter + 1; sc.session })

      val starting = dummyProperty1("starting property", step = incrementEffect)
      val otherAction = dummyProperty1("other property")
      val transitions = Map(starting -> ((100, otherAction) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = maxRun, 1, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: SuccessScenarioReport =>
          assert(f.isSuccess)
          assert(uglyCounter == maxRun)

        case _ =>
          assertMatch(res) { case _: SuccessScenarioReport => }
      }
    }

    test("always terminates with maxNumberOfTransitions (even with cyclic model)") {
      val maxTransition = 100
      var uglyCounter = 0
      val incrementEffect: Step = EffectStep.fromSync("identity", sc => { uglyCounter = uglyCounter + 1; sc.session })

      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property", step = incrementEffect)
      val otherActionTwo = dummyProperty1("other property two ", step = incrementEffect)
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((100, otherActionTwo) :: Nil),
        otherActionTwo -> ((100, otherAction) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = 1, maxTransition, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: SuccessScenarioReport =>
          assert(f.isSuccess)
          assert(uglyCounter == maxTransition)

        case _ =>
          assertMatch(res) { case _: SuccessScenarioReport => }
      }
    }

    test("report a failure when an action explodes") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property", step = brokenEffect)
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((100, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = 10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg == """Scenario 'scenario with checkStep' failed:
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

    test("report a failure when no precondition is valid") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property", preNeverValid = true)
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((100, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(integerGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = 10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: FailureScenarioReport =>
          assert(!f.isSuccess)
          assert(f.msg ==
            """Scenario 'scenario with checkStep' failed:
              |
              |at step:
              |Checking model 'model with empty transition for starting' with maxNumberOfRuns=10 and maxNumberOfTransitions=10
              |
              |with error(s):
              |No outgoing transition found from `starting property` to another property with valid pre-conditions
              |
              |seed for the run was '1'
              |""".stripMargin)
        case _ =>
          assertMatch(res) { case _: FailureScenarioReport => }
      }
    }

    test("not using a generator should really not call it") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property")
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((100, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      // passing a broken gen but the actions are not calling it...should be good!
      val modelRunner = ModelRunner.make(brokenIntGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = 10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

      val res = awaitTask(ScenarioRunner.runScenario(Session.newEmpty)(s))
      res match {
        case f: SuccessScenarioReport =>
          assert(f.isSuccess)

        case _ =>
          assertMatch(res) { case _: SuccessScenarioReport => }
      }
    }

    test("fails the test if the gen throws") {
      val starting = dummyProperty1("starting property")
      val otherAction = dummyProperty1("other property", callGen = true)
      val transitions = Map(
        starting -> ((100, otherAction) :: Nil),
        otherAction -> ((100, starting) :: Nil))
      val model = Model("model with empty transition for starting", starting, transitions)
      val modelRunner = ModelRunner.make(brokenIntGen)(model)
      val checkStep = CheckModelStep(maxNumberOfRuns = 10, 10, modelRunner)
      val s = Scenario("scenario with checkStep", checkStep :: Nil)

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
