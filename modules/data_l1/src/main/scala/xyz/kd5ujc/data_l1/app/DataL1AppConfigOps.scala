package xyz.kd5ujc.data_l1.app

import xyz.kd5ujc.shared_data.app.ApplicationConfigOps._

import pureconfig._
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

object DataL1AppConfigOps {
  implicit val applicationConfigReader: ConfigReader[DataL1AppConfig] = deriveReader

  implicit val dataL1ProductHint: ProductHint[DataL1AppConfig] =
    ProductHint[DataL1AppConfig](ConfigFieldMapping {
      case "ml0Peers" => "ml0-peers"
      case v          => ConfigFieldMapping(CamelCase, KebabCase)(v)
    })
}
