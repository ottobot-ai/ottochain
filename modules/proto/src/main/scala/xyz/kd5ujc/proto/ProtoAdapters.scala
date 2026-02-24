package xyz.kd5ujc.proto

import java.util.UUID

import cats.syntax.all._

import scala.util.Try

import io.constellationnetwork.metagraph_sdk.json_logic.{JsonLogicExpression, JsonLogicValue}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.security.hash.Hash

import xyz.kd5ujc.schema.Records
import xyz.kd5ujc.schema.fiber.FiberLogEntry.{EventReceipt, OracleInvocation}
import xyz.kd5ujc.schema.fiber._

import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Json
import io.circe.syntax._
import ottochain.v1.{
  AccessControlPolicy => ProtoAccessControl,
  Address => ProtoAddress,
  EmittedEvent => ProtoEmittedEvent,
  EventReceipt => ProtoEventReceipt,
  FiberOrdinal => ProtoFiberOrdinal,
  FiberStatus => ProtoFiberStatus,
  HashValue => ProtoHash,
  ScriptFiberRecord => ProtoScriptRecord,
  ScriptInvocation => ProtoScriptInvocation,
  SnapshotOrdinal => ProtoSnapshotOrdinal,
  StateId => ProtoStateId,
  StateMachineDefinition => ProtoSMDefinition,
  StateMachineFiberRecord => ProtoSMRecord
}

/**
 * Bidirectional converters between proto-generated (ScalaPB) types and
 * the fiber engine's rich runtime types.
 *
 * Conversion strategy for JSON-typed fields (JsonLogicValue, JsonLogicExpression):
 *   Runtime ──[Circe Encoder]──▶ io.circe.Json ──▶ com.google.protobuf.struct.Value
 *   com.google.protobuf.struct.Value ──▶ io.circe.Json ──[Circe Decoder]──▶ Runtime
 *
 * StateMachineDefinition is encoded/decoded via its Circe codecs (derevo-derived).
 */
object ProtoAdapters {

  // ─────────────────────────────────────────────────────────────────────
  // FiberStatus
  // ─────────────────────────────────────────────────────────────────────

  def toProtoFiberStatus(status: FiberStatus): ProtoFiberStatus = status match {
    case FiberStatus.Active   => ProtoFiberStatus.FIBER_STATUS_ACTIVE
    case FiberStatus.Archived => ProtoFiberStatus.FIBER_STATUS_ARCHIVED
    case FiberStatus.Failed   => ProtoFiberStatus.FIBER_STATUS_FAILED
  }

  def fromProtoFiberStatus(status: ProtoFiberStatus): Either[String, FiberStatus] = status match {
    case ProtoFiberStatus.FIBER_STATUS_ACTIVE   => Right(FiberStatus.Active)
    case ProtoFiberStatus.FIBER_STATUS_ARCHIVED => Right(FiberStatus.Archived)
    case ProtoFiberStatus.FIBER_STATUS_FAILED   => Right(FiberStatus.Failed)
    case other                                  => Left(s"Unknown FiberStatus: $other")
  }

  // ─────────────────────────────────────────────────────────────────────
  // Ordinals, Hash, Address, StateId
  // ─────────────────────────────────────────────────────────────────────

  def toProtoSnapshotOrdinal(ord: SnapshotOrdinal): ProtoSnapshotOrdinal =
    ProtoSnapshotOrdinal(ord.value.value)

  def fromProtoSnapshotOrdinal(ord: ProtoSnapshotOrdinal): SnapshotOrdinal =
    SnapshotOrdinal(NonNegLong.unsafeFrom(ord.value))

  def toProtoFiberOrdinal(ord: FiberOrdinal): ProtoFiberOrdinal =
    ProtoFiberOrdinal(ord.value.value)

  def fromProtoFiberOrdinal(ord: ProtoFiberOrdinal): Either[String, FiberOrdinal] =
    FiberOrdinal(ord.value).toRight(s"Invalid FiberOrdinal value: ${ord.value}")

  def toProtoHash(hash: Hash): ProtoHash = ProtoHash(hash.value)
  def fromProtoHash(h:  ProtoHash): Hash = Hash(h.value)

  def toProtoAddress(addr: Address): ProtoAddress = ProtoAddress(addr.value.value)

  def fromProtoAddress(a: ProtoAddress): Address =
    Address(refineV[DAGAddressRefined].unsafeFrom(a.value))

  def toProtoStateId(sid:   StateId): ProtoStateId = ProtoStateId(sid.value)
  def fromProtoStateId(sid: ProtoStateId): StateId = StateId(sid.value)

  // ─────────────────────────────────────────────────────────────────────
  // Circe Json ↔ com.google.protobuf.struct.Value / Struct
  // ─────────────────────────────────────────────────────────────────────

  def circeJsonToProtoValue(json: Json): com.google.protobuf.struct.Value =
    json.fold(
      jsonNull = com.google.protobuf.struct
        .Value()
        .withNullValue(
          com.google.protobuf.struct.NullValue.NULL_VALUE
        ),
      jsonBoolean = b => com.google.protobuf.struct.Value().withBoolValue(b),
      jsonNumber = n => com.google.protobuf.struct.Value().withNumberValue(n.toDouble),
      jsonString = s => com.google.protobuf.struct.Value().withStringValue(s),
      jsonArray = arr =>
        com.google.protobuf.struct
          .Value()
          .withListValue(
            com.google.protobuf.struct.ListValue(arr.map(circeJsonToProtoValue))
          ),
      jsonObject = obj =>
        com.google.protobuf.struct
          .Value()
          .withStructValue(
            com.google.protobuf.struct.Struct(
              obj.toMap.view.mapValues(circeJsonToProtoValue).toMap
            )
          )
    )

  def protoValueToCirceJson(v: com.google.protobuf.struct.Value): Json = {
    import com.google.protobuf.struct.Value.Kind
    v.kind match {
      case Kind.NullValue(_)   => Json.Null
      case Kind.BoolValue(b)   => Json.fromBoolean(b)
      case Kind.NumberValue(n) => Json.fromDoubleOrNull(n)
      case Kind.StringValue(s) => Json.fromString(s)
      case Kind.ListValue(l)   => Json.fromValues(l.values.map(protoValueToCirceJson))
      case Kind.StructValue(s) =>
        Json.fromFields(s.fields.view.mapValues(protoValueToCirceJson).toList)
      case Kind.Empty => Json.Null
    }
  }

  def jsonToProtoStruct(json: Json): com.google.protobuf.struct.Struct =
    json.asObject
      .map(obj => com.google.protobuf.struct.Struct(obj.toMap.view.mapValues(circeJsonToProtoValue).toMap))
      .getOrElse(com.google.protobuf.struct.Struct())

  def protoStructToJson(s: com.google.protobuf.struct.Struct): Json =
    Json.fromFields(s.fields.view.mapValues(protoValueToCirceJson).toList)

  // ─────────────────────────────────────────────────────────────────────
  // JsonLogicValue / JsonLogicExpression ↔ protobuf.Value
  // ─────────────────────────────────────────────────────────────────────

  def jsonLogicToProtoValue(jlv: JsonLogicValue): com.google.protobuf.struct.Value =
    circeJsonToProtoValue(jlv.asJson)

  def protoValueToJsonLogic(v: com.google.protobuf.struct.Value): Either[String, JsonLogicValue] =
    protoValueToCirceJson(v).as[JsonLogicValue].left.map(_.message)

  def jsonLogicExprToProtoValue(expr: JsonLogicExpression): com.google.protobuf.struct.Value =
    circeJsonToProtoValue(expr.asJson)

  def protoValueToJsonLogicExpr(
    v: com.google.protobuf.struct.Value
  ): Either[String, JsonLogicExpression] =
    protoValueToCirceJson(v).as[JsonLogicExpression].left.map(_.message)

  // ─────────────────────────────────────────────────────────────────────
  // StateMachineDefinition ↔ ProtoSMDefinition
  // ─────────────────────────────────────────────────────────────────────

  def toProtoSMDefinition(d: StateMachineDefinition): ProtoSMDefinition = {
    val statesJson = Json.fromFields(
      d.states.toList.map { case (sid, state) => sid.value -> state.asJson }
    )
    ProtoSMDefinition(
      states = Some(jsonToProtoStruct(statesJson)),
      initialState = Some(toProtoStateId(d.initialState)),
      transitions = d.transitions.map(t => jsonToProtoStruct(t.asJson)),
      metadata = d.metadata.map(m => jsonToProtoStruct(m.asJson))
    )
  }

  def fromProtoSMDefinition(p: ProtoSMDefinition): Either[String, StateMachineDefinition] =
    for {
      initialState <- p.initialState
        .toRight("Missing initialState in StateMachineDefinition")
        .map(fromProtoStateId)
      statesJson = p.states.map(protoStructToJson).getOrElse(Json.obj())
      statesMap <- statesJson.asObject
        .toRight("StateMachineDefinition.states is not a JSON object")
        .flatMap { obj =>
          obj.toList
            .traverse { case (k, v) =>
              v.as[State].bimap(_.message, state => StateId(k) -> state)
            }
            .map(_.toMap)
        }
      transitions <- p.transitions.toList.traverse { s =>
        protoStructToJson(s).as[Transition].left.map(_.message)
      }
      metadata <- p.metadata.traverse { s =>
        protoStructToJson(s).as[JsonLogicValue].left.map(_.message)
      }
    } yield StateMachineDefinition(
      states = statesMap,
      initialState = initialState,
      transitions = transitions,
      metadata = metadata
    )

  // ─────────────────────────────────────────────────────────────────────
  // AccessControlPolicy
  // ─────────────────────────────────────────────────────────────────────

  def toProtoAccessControl(acp: AccessControlPolicy): ProtoAccessControl = acp match {
    case AccessControlPolicy.Public =>
      ProtoAccessControl().withPublic(ottochain.v1.PublicAccess())
    case AccessControlPolicy.Whitelist(addresses) =>
      ProtoAccessControl().withWhitelist(
        ottochain.v1.WhitelistAccess(addresses.map(toProtoAddress).toSeq)
      )
    case AccessControlPolicy.FiberOwned(fiberId) =>
      ProtoAccessControl().withFiberOwned(
        ottochain.v1.FiberOwnedAccess(fiberId.toString)
      )
  }

  def fromProtoAccessControl(
    p: ProtoAccessControl
  ): Either[String, AccessControlPolicy] = {
    import ottochain.v1.AccessControlPolicy.Policy
    p.policy match {
      case Policy.Public(_) => Right(AccessControlPolicy.Public)
      case Policy.Whitelist(wl) =>
        Right(AccessControlPolicy.Whitelist(wl.addresses.map(fromProtoAddress).toSet))
      case Policy.FiberOwned(fo) =>
        Try(UUID.fromString(fo.fiberId)).toEither
          .bimap(e => s"Invalid UUID in FiberOwnedAccess: ${e.getMessage}", AccessControlPolicy.FiberOwned)
      case Policy.Empty => Left("AccessControlPolicy has empty policy")
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // EmittedEvent
  // ─────────────────────────────────────────────────────────────────────

  def toProtoEmittedEvent(e: EmittedEvent): ProtoEmittedEvent =
    ProtoEmittedEvent(
      name = e.name,
      data = Some(jsonLogicToProtoValue(e.data)),
      destination = e.destination
    )

  def fromProtoEmittedEvent(p: ProtoEmittedEvent): Either[String, EmittedEvent] =
    p.data
      .toRight("EmittedEvent missing data field")
      .flatMap(protoValueToJsonLogic)
      .map(data =>
        EmittedEvent(
          name = p.name,
          data = data,
          destination = p.destination
        )
      )

  // ─────────────────────────────────────────────────────────────────────
  // EventReceipt
  // ─────────────────────────────────────────────────────────────────────

  def toProtoEventReceipt(er: EventReceipt): ProtoEventReceipt =
    ProtoEventReceipt(
      fiberId = er.fiberId.toString,
      sequenceNumber = Some(toProtoFiberOrdinal(er.sequenceNumber)),
      eventName = er.eventName,
      ordinal = Some(toProtoSnapshotOrdinal(er.ordinal)),
      fromState = Some(toProtoStateId(er.fromState)),
      toState = Some(toProtoStateId(er.toState)),
      success = er.success,
      gasUsed = er.gasUsed,
      triggersFired = er.triggersFired,
      errorMessage = er.errorMessage,
      sourceFiberId = er.sourceFiberId.map(_.toString),
      emittedEvents = er.emittedEvents.map(toProtoEmittedEvent)
    )

  def fromProtoEventReceipt(p: ProtoEventReceipt): Either[String, EventReceipt] =
    for {
      fiberId <- Try(UUID.fromString(p.fiberId)).toEither.left.map(e =>
        s"Invalid UUID in EventReceipt.fiberId: ${e.getMessage}"
      )
      sequenceNumber <- p.sequenceNumber
        .toRight("Missing sequenceNumber in EventReceipt")
        .flatMap(fromProtoFiberOrdinal)
      sourceFiberId <- p.sourceFiberId.traverse(s =>
        Try(UUID.fromString(s)).toEither.left.map(e => s"Invalid UUID in EventReceipt.sourceFiberId: ${e.getMessage}")
      )
      emittedEvents <- p.emittedEvents.toList.traverse(fromProtoEmittedEvent)
    } yield EventReceipt(
      fiberId = fiberId,
      sequenceNumber = sequenceNumber,
      eventName = p.eventName,
      ordinal = p.ordinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      fromState = p.fromState.map(fromProtoStateId).getOrElse(StateId("")),
      toState = p.toState.map(fromProtoStateId).getOrElse(StateId("")),
      success = p.success,
      gasUsed = p.gasUsed,
      triggersFired = p.triggersFired,
      errorMessage = p.errorMessage,
      sourceFiberId = sourceFiberId,
      emittedEvents = emittedEvents
    )

  // ─────────────────────────────────────────────────────────────────────
  // OracleInvocation ↔ ScriptInvocation
  // ─────────────────────────────────────────────────────────────────────

  def toProtoScriptInvocation(oi: OracleInvocation): ProtoScriptInvocation =
    ProtoScriptInvocation(
      fiberId = oi.fiberId.toString,
      method = oi.method,
      args = Some(jsonLogicToProtoValue(oi.args)),
      result = Some(jsonLogicToProtoValue(oi.result)),
      gasUsed = oi.gasUsed,
      invokedAt = Some(toProtoSnapshotOrdinal(oi.invokedAt)),
      invokedBy = Some(toProtoAddress(oi.invokedBy))
    )

  def fromProtoScriptInvocation(p: ProtoScriptInvocation): Either[String, OracleInvocation] =
    for {
      fiberId <- Try(UUID.fromString(p.fiberId)).toEither.left.map(e =>
        s"Invalid UUID in ScriptInvocation.fiberId: ${e.getMessage}"
      )
      args   <- p.args.toRight("Missing args in ScriptInvocation").flatMap(protoValueToJsonLogic)
      result <- p.result.toRight("Missing result in ScriptInvocation").flatMap(protoValueToJsonLogic)
    } yield OracleInvocation(
      fiberId = fiberId,
      method = p.method,
      args = args,
      result = result,
      gasUsed = p.gasUsed,
      invokedAt = p.invokedAt
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      invokedBy = p.invokedBy
        .map(fromProtoAddress)
        .getOrElse(Address(refineV[DAGAddressRefined].unsafeFrom("DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG")))
    )

  // ─────────────────────────────────────────────────────────────────────
  // StateMachineFiberRecord ↔ Proto
  // ─────────────────────────────────────────────────────────────────────

  def toProtoSMRecord(record: Records.StateMachineFiberRecord): ProtoSMRecord =
    ProtoSMRecord(
      fiberId = record.fiberId.toString,
      creationOrdinal = Some(toProtoSnapshotOrdinal(record.creationOrdinal)),
      previousUpdateOrdinal = Some(toProtoSnapshotOrdinal(record.previousUpdateOrdinal)),
      latestUpdateOrdinal = Some(toProtoSnapshotOrdinal(record.latestUpdateOrdinal)),
      definition = Some(toProtoSMDefinition(record.definition)),
      currentState = Some(toProtoStateId(record.currentState)),
      stateData = Some(jsonLogicToProtoValue(record.stateData)),
      stateDataHash = Some(toProtoHash(record.stateDataHash)),
      sequenceNumber = Some(toProtoFiberOrdinal(record.sequenceNumber)),
      owners = record.owners.map(toProtoAddress).toSeq,
      status = toProtoFiberStatus(record.status),
      lastReceipt = record.lastReceipt.map(toProtoEventReceipt),
      parentFiberId = record.parentFiberId.map(_.toString),
      childFiberIds = record.childFiberIds.map(_.toString).toSeq
    )

  def fromProtoSMRecord(
    p: ProtoSMRecord
  ): Either[String, Records.StateMachineFiberRecord] =
    for {
      fiberId <- Try(UUID.fromString(p.fiberId)).toEither.left.map(e =>
        s"Invalid UUID in SMRecord.fiberId: ${e.getMessage}"
      )
      definition <- p.definition.toRight("Missing definition in SMRecord").flatMap(fromProtoSMDefinition)
      stateData  <- p.stateData.toRight("Missing stateData in SMRecord").flatMap(protoValueToJsonLogic)
      sequenceNumber <- p.sequenceNumber
        .toRight("Missing sequenceNumber in SMRecord")
        .flatMap(fromProtoFiberOrdinal)
      status      <- fromProtoFiberStatus(p.status)
      lastReceipt <- p.lastReceipt.traverse(fromProtoEventReceipt)
      parentFiberId <- p.parentFiberId.traverse(s =>
        Try(UUID.fromString(s)).toEither.left.map(e => s"Invalid UUID in parentFiberId: ${e.getMessage}")
      )
      childFiberIds <- p.childFiberIds.toList.traverse(s =>
        Try(UUID.fromString(s)).toEither.left.map(e => s"Invalid UUID in childFiberIds: ${e.getMessage}")
      )
    } yield Records.StateMachineFiberRecord(
      fiberId = fiberId,
      creationOrdinal = p.creationOrdinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      previousUpdateOrdinal = p.previousUpdateOrdinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      latestUpdateOrdinal = p.latestUpdateOrdinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      definition = definition,
      currentState = p.currentState.map(fromProtoStateId).getOrElse(StateId("")),
      stateData = stateData,
      stateDataHash = p.stateDataHash.map(fromProtoHash).getOrElse(Hash("")),
      sequenceNumber = sequenceNumber,
      owners = p.owners.map(fromProtoAddress).toSet,
      status = status,
      lastReceipt = lastReceipt,
      parentFiberId = parentFiberId,
      childFiberIds = childFiberIds.toSet
    )

  // ─────────────────────────────────────────────────────────────────────
  // ScriptFiberRecord ↔ Proto
  // ─────────────────────────────────────────────────────────────────────

  def toProtoScriptRecord(record: Records.ScriptFiberRecord): ProtoScriptRecord =
    ProtoScriptRecord(
      fiberId = record.fiberId.toString,
      creationOrdinal = Some(toProtoSnapshotOrdinal(record.creationOrdinal)),
      latestUpdateOrdinal = Some(toProtoSnapshotOrdinal(record.latestUpdateOrdinal)),
      scriptProgram = Some(jsonLogicExprToProtoValue(record.scriptProgram)),
      stateData = record.stateData.map(jsonLogicToProtoValue),
      stateDataHash = record.stateDataHash.map(toProtoHash),
      accessControl = Some(toProtoAccessControl(record.accessControl)),
      sequenceNumber = Some(toProtoFiberOrdinal(record.sequenceNumber)),
      owners = record.owners.map(toProtoAddress).toSeq,
      status = toProtoFiberStatus(record.status),
      lastInvocation = record.lastInvocation.map(toProtoScriptInvocation)
    )

  def fromProtoScriptRecord(
    p: ProtoScriptRecord
  ): Either[String, Records.ScriptFiberRecord] =
    for {
      fiberId <- Try(UUID.fromString(p.fiberId)).toEither.left.map(e =>
        s"Invalid UUID in ScriptRecord.fiberId: ${e.getMessage}"
      )
      scriptProgram <- p.scriptProgram
        .toRight("Missing scriptProgram in ScriptRecord")
        .flatMap(protoValueToJsonLogicExpr)
      stateData <- p.stateData.traverse(protoValueToJsonLogic)
      accessControl <- p.accessControl
        .toRight("Missing accessControl in ScriptRecord")
        .flatMap(fromProtoAccessControl)
      sequenceNumber <- p.sequenceNumber
        .toRight("Missing sequenceNumber in ScriptRecord")
        .flatMap(fromProtoFiberOrdinal)
      status         <- fromProtoFiberStatus(p.status)
      lastInvocation <- p.lastInvocation.traverse(fromProtoScriptInvocation)
    } yield Records.ScriptFiberRecord(
      fiberId = fiberId,
      creationOrdinal = p.creationOrdinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      latestUpdateOrdinal = p.latestUpdateOrdinal
        .map(fromProtoSnapshotOrdinal)
        .getOrElse(SnapshotOrdinal(NonNegLong.MinValue)),
      scriptProgram = scriptProgram,
      stateData = stateData,
      stateDataHash = p.stateDataHash.map(fromProtoHash),
      accessControl = accessControl,
      sequenceNumber = sequenceNumber,
      owners = p.owners.map(fromProtoAddress).toSet,
      status = status,
      lastInvocation = lastInvocation
    )
}
