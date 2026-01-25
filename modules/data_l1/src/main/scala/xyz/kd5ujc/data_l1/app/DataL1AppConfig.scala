package xyz.kd5ujc.data_l1.app

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.P2PContext

import xyz.kd5ujc.shared_data.app.SharedAppConfig

case class DataL1AppConfig(
  node:        SharedAppConfig.NodeConfig,
  ml0Peers:    Set[P2PContext],
  metagraphId: Address
) extends SharedAppConfig

object DataL1AppConfig {}
