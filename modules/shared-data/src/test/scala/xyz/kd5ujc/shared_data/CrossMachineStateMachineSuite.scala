package xyz.kd5ujc.shared_data

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{DataState, L0NodeContext}
import io.constellationnetwork.metagraph_sdk.json_logic.JsonLogicOp._
import io.constellationnetwork.metagraph_sdk.json_logic._
import io.constellationnetwork.metagraph_sdk.std.JsonBinaryHasher.HasherOps
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.signature.Signed

import xyz.kd5ujc.schema.{CalculatedState, OnChain, Records, StateMachine, Updates}
import xyz.kd5ujc.shared_data.lifecycle.Combiner
import xyz.kd5ujc.shared_test.Participant._
import xyz.kd5ujc.shared_test.TestFixture

import weaver.SimpleIOSuite

object CrossMachineStateMachineSuite extends SimpleIOSuite {

  test("cross-machine: escrow with seller dependency") {
    TestFixture.resource().use { fixture =>
      implicit val s: SecurityProvider[IO] = fixture.securityProvider
      implicit val l0ctx: L0NodeContext[IO] = fixture.l0Context
      val registry = fixture.registry
      val ordinal = fixture.ordinal
      for {
        combiner <- Combiner.make[IO].pure[IO]

        sellerCid <- UUIDGen.randomUUID[IO]
        sellerDef = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("holding")  -> StateMachine.State(StateMachine.StateId("holding")),
            StateMachine.StateId("released") -> StateMachine.State(StateMachine.StateId("released"))
          ),
          initialState = StateMachine.StateId("holding"),
          transitions = List.empty
        )

        sellerData = MapValue(
          Map(
            "hasItem"  -> BoolValue(true),
            "itemName" -> StrValue("widget")
          )
        )
        sellerHash <- (sellerData: JsonLogicValue).computeDigest

        sellerFiber = Records.StateMachineFiberRecord(
          cid = sellerCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = sellerDef,
          currentState = StateMachine.StateId("holding"),
          stateData = sellerData,
          stateDataHash = sellerHash,
          sequenceNumber = 0,
          owners = Set(Alice).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        buyerCid <- UUIDGen.randomUUID[IO]
        buyerDef = StateMachine.StateMachineDefinition(
          states = Map(
            StateMachine.StateId("pending")   -> StateMachine.State(StateMachine.StateId("pending")),
            StateMachine.StateId("purchased") -> StateMachine.State(StateMachine.StateId("purchased"))
          ),
          initialState = StateMachine.StateId("pending"),
          transitions = List(
            StateMachine.Transition(
              from = StateMachine.StateId("pending"),
              to = StateMachine.StateId("purchased"),
              eventType = StateMachine.EventType("buy"),
              guard = ApplyExpression(
                AndOp,
                List(
                  VarExpression(Left(s"machines.$sellerCid.state.hasItem")),
                  ApplyExpression(
                    Geq,
                    List(
                      VarExpression(Left("state.balance")),
                      ConstExpression(IntValue(100))
                    )
                  )
                )
              ),
              effect = ConstExpression(
                MapValue(
                  Map(
                    "balance"   -> IntValue(0),
                    "purchased" -> BoolValue(true)
                  )
                )
              ),
              dependencies = Set(sellerCid)
            )
          )
        )

        buyerData = MapValue(
          Map(
            "balance" -> IntValue(150)
          )
        )
        buyerHash <- (buyerData: JsonLogicValue).computeDigest

        buyerFiber = Records.StateMachineFiberRecord(
          cid = buyerCid,
          creationOrdinal = ordinal,
          previousUpdateOrdinal = ordinal,
          latestUpdateOrdinal = ordinal,
          definition = buyerDef,
          currentState = StateMachine.StateId("pending"),
          stateData = buyerData,
          stateDataHash = buyerHash,
          sequenceNumber = 0,
          owners = Set(Bob).map(registry.addresses),
          status = Records.FiberStatus.Active,
          lastEventStatus = Records.EventProcessingStatus.Initialized
        )

        inState = DataState(
          OnChain(Map(sellerCid -> sellerHash, buyerCid -> buyerHash)),
          CalculatedState(
            Map(
              sellerCid -> sellerFiber,
              buyerCid  -> buyerFiber
            ),
            Map.empty
          )
        )

        buyEvent = StateMachine.Event(
          eventType = StateMachine.EventType("buy"),
          payload = MapValue(Map.empty[String, JsonLogicValue])
        )
        buyUpdate = Updates.ProcessFiberEvent(buyerCid, buyEvent)
        buyProof   <- registry.generateProofs(buyUpdate, Set(Bob))
        finalState <- combiner.insert(inState, Signed(buyUpdate, buyProof))

        updatedBuyer = finalState.calculated.stateMachines
          .get(buyerCid)
          .collect { case r: Records.StateMachineFiberRecord => r }

        buyerBalance: Option[BigInt] = updatedBuyer.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("balance").collect { case IntValue(b) => b }
            case _           => None
          }
        }
        buyerPurchased: Option[Boolean] = updatedBuyer.flatMap { f =>
          f.stateData match {
            case MapValue(m) => m.get("purchased").collect { case BoolValue(p) => p }
            case _           => None
          }
        }
      } yield expect(updatedBuyer.isDefined) and
      expect(updatedBuyer.map(_.currentState).contains(StateMachine.StateId("purchased"))) and
      expect(buyerBalance.contains(BigInt(0))) and
      expect(buyerPurchased.contains(true))
    }
  }
}
