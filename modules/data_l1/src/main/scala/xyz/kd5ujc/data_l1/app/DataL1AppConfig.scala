package xyz.kd5ujc.data_l1.app

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.P2PContext

import xyz.kd5ujc.shared_data.app.ApplicationConfig

case class DataL1AppConfig(
  node:        ApplicationConfig.NodeConfig,
  aws:         ApplicationConfig.AmazonWebServicesConfig,
  ml0Peers:    Set[P2PContext],
  metagraphId: Address
) extends ApplicationConfig {
  override def nodeOpt: Option[ApplicationConfig.NodeConfig] = Some(node)
}

object DataL1AppConfig {}
