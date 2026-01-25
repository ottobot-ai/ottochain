package xyz.kd5ujc.metagraph_l0.app

import xyz.kd5ujc.shared_data.app.SharedAppConfig

case class ML0AppConfig(
  node: SharedAppConfig.NodeConfig
) extends SharedAppConfig

object ML0AppConfig {}
