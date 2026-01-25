package xyz.kd5ujc.shared_data.app

import io.constellationnetwork.schema.peer.P2PContext

import ciris.Secret
import com.comcast.ip4s.Port
import fs2.io.file.Path

abstract class SharedAppConfig {
  def node: SharedAppConfig.NodeConfig
}

object SharedAppConfig {

  case class BaseApplicationConfig(
    node: SharedAppConfig.NodeConfig
  ) extends SharedAppConfig

  case class NodeConfig(
    key:               NodeConfig.Key,
    network:           NodeConfig.Network,
    tessellationPorts: NodeConfig.TessellationPorts,
    globalPeers:       List[P2PContext]
  )

  case class AmazonWebServicesConfig(
    s3:          AmazonWebServicesConfig.S3,
    athena:      AmazonWebServicesConfig.Athena,
    credentials: AmazonWebServicesConfig.Credentials
  )

  object NodeConfig {
    case class Key(path: Path, alias: Secret[String], password: Secret[String])
    case class TessellationPorts(public: Port, p2p: Port, cli: Port)
    case class Network(appEnv: String, collateral: Int)
  }

  object AmazonWebServicesConfig {
    case class Athena(region: String, database: String, table: String, data: String, response: String)
    case class Credentials(id: String, secret: String)
    case class S3(region: String, bucketName: String)
  }
}
