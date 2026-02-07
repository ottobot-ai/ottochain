package xyz.kd5ujc.schema.market

import enumeratum.{CirceEnum, Enum, EnumEntry}

sealed trait MarketType extends EnumEntry

object MarketType extends Enum[MarketType] with CirceEnum[MarketType] {
  val values: IndexedSeq[MarketType] = findValues

  case object Prediction extends MarketType
  case object Auction extends MarketType
  case object Crowdfund extends MarketType
  case object GroupBuy extends MarketType
}

sealed trait MarketState extends EnumEntry

object MarketState extends Enum[MarketState] with CirceEnum[MarketState] {
  val values: IndexedSeq[MarketState] = findValues

  case object Proposed extends MarketState
  case object Open extends MarketState
  case object Closed extends MarketState
  case object Resolving extends MarketState
  case object Settled extends MarketState
  case object Refunded extends MarketState
  case object Cancelled extends MarketState
}
