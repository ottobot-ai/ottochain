package xyz.kd5ujc.proto

import cats.effect.IO

import io.constellationnetwork.currency.dataApplication.DataUpdate

import ottochain.v1._
import weaver.SimpleIOSuite

object ScalaPBIntegrationTest extends SimpleIOSuite {

  test("Generated CreateStateMachine extends DataUpdate") {
    IO {
      val createSM = CreateStateMachine(
        fiberId = "test-fiber-id",
        definition = None,
        initialData = None,
        parentFiberId = None
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = createSM

      expect(createSM.fiberId == "test-fiber-id") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated TransitionStateMachine extends DataUpdate") {
    IO {
      val transitionSM = TransitionStateMachine(
        fiberId = "test-fiber-id",
        eventName = "test-event",
        payload = None,
        targetSequenceNumber = None
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = transitionSM

      expect(transitionSM.fiberId == "test-fiber-id") &&
      expect(transitionSM.eventName == "test-event") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated ArchiveStateMachine extends DataUpdate") {
    IO {
      val archiveSM = ArchiveStateMachine(
        fiberId = "test-archive-id",
        targetSequenceNumber = Some(FiberOrdinal(value = 42))
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = archiveSM

      expect(archiveSM.fiberId == "test-archive-id") &&
      expect(archiveSM.targetSequenceNumber.exists(_.value == 42)) &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated CreateScript extends DataUpdate") {
    IO {
      val createScript = CreateScript(
        fiberId = "test-script-id",
        scriptProgram = None,
        initialState = None,
        accessControl = Some(AccessControlPolicy())
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = createScript

      expect(createScript.fiberId == "test-script-id") &&
      expect(createScript.accessControl.isDefined) &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated InvokeScript extends DataUpdate") {
    IO {
      val invokeScript = InvokeScript(
        fiberId = "test-invoke-id",
        method = "execute",
        args = None,
        targetSequenceNumber = Some(FiberOrdinal(value = 10))
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = invokeScript

      expect(invokeScript.fiberId == "test-invoke-id") &&
      expect(invokeScript.method == "execute") &&
      expect(invokeScript.targetSequenceNumber.exists(_.value == 10)) &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated OttochainMessage union extends DataUpdate") {
    IO {
      // Test with CreateStateMachine variant
      val createSM = CreateStateMachine(
        fiberId = "union-test-id",
        definition = None,
        initialData = None,
        parentFiberId = None
      )

      val ottochainMessage = OttochainMessage(
        message = OttochainMessage.Message.CreateStateMachine(createSM)
      )

      // This should compile successfully if DataUpdate mixin works
      val dataUpdate: DataUpdate = ottochainMessage

      expect(ottochainMessage.message.isCreateStateMachine) &&
      expect(ottochainMessage.message.createStateMachine.exists(_.fiberId == "union-test-id")) &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }
}
