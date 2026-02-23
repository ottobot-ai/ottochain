package xyz.kd5ujc.proto

import cats.effect.IO

import io.constellationnetwork.currency.dataApplication.DataUpdate

import ottochain.v1.{
  ArchiveStateMachine,
  CreateScript,
  CreateStateMachine,
  InvokeScript,
  OttochainMessage,
  TransitionStateMachine
}
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
        fiberId = "test-fiber-id",
        targetSequenceNumber = None
      )

      // Structural check: generated type satisfies DataUpdate contract
      val dataUpdate: DataUpdate = archiveSM

      expect(archiveSM.fiberId == "test-fiber-id") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated CreateScript extends DataUpdate") {
    IO {
      val createScript = CreateScript(
        fiberId = "test-fiber-id",
        scriptProgram = None,
        initialState = None,
        accessControl = None
      )

      val dataUpdate: DataUpdate = createScript

      expect(createScript.fiberId == "test-fiber-id") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated InvokeScript extends DataUpdate") {
    IO {
      val invokeScript = InvokeScript(
        fiberId = "test-fiber-id",
        method = "execute",
        args = None,
        targetSequenceNumber = None
      )

      val dataUpdate: DataUpdate = invokeScript

      expect(invokeScript.fiberId == "test-fiber-id") &&
      expect(invokeScript.method == "execute") &&
      expect(dataUpdate.isInstanceOf[DataUpdate])
    }
  }

  test("Generated OttochainMessage union type extends DataUpdate") {
    IO {
      // The outer union wrapper itself also has the DataUpdate mixin.
      // Verify that an OttochainMessage wrapping any inner variant
      // satisfies the DataUpdate type constraint.
      val innerSM = CreateStateMachine(
        fiberId = "union-test-fiber",
        definition = None,
        initialData = None,
        parentFiberId = None
      )
      val msg = OttochainMessage(
        message = OttochainMessage.Message.CreateStateMachine(innerSM)
      )

      val dataUpdate: DataUpdate = msg

      expect(msg.isInstanceOf[DataUpdate]) &&
      expect(dataUpdate.isInstanceOf[DataUpdate]) &&
      expect(msg.message.createStateMachine.exists(_.fiberId == "union-test-fiber"))
    }
  }
}
