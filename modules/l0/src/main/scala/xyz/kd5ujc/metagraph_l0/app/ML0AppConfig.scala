package xyz.kd5ujc.metagraph_l0.app

import xyz.kd5ujc.shared_data.app.ApplicationConfig

case class ML0AppConfig(
  node: ApplicationConfig.NodeConfig,
  aws:  ApplicationConfig.AmazonWebServicesConfig
) extends ApplicationConfig {
  override def nodeOpt: Option[ApplicationConfig.NodeConfig] = Some(node)
}

object ML0AppConfig {}
