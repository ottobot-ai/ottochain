package xyz.kd5ujc.shared_data.app

import cats.effect.kernel.Sync
import cats.implicits.toBifunctorOps

import scala.reflect.ClassTag

import io.constellationnetwork.node.shared.config.types.{HttpClientConfig, HttpServerConfig}
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.peer.{P2PContext, PeerId}
import io.constellationnetwork.security.hex.Hex

import xyz.kd5ujc.shared_data.app.ApplicationConfig.AmazonWebServicesConfig
import xyz.kd5ujc.shared_data.app.ApplicationConfig.NodeConfig.TessellationPorts

import ciris.Secret
import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.refineV
import fs2.io.file.Path
import org.http4s.Uri
import pureconfig._
import pureconfig.error.CannotConvert
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader
import pureconfig.module.catseffect.syntax._

object ApplicationConfigOps {

  def readDefault[F[_]: Sync, T: ClassTag](implicit ev: ConfigReader[T]): F[T] =
    ConfigSource.default
      .loadF[F, T]()

  def loadConfig[F[_]: Sync, T: ClassTag](customConf: Option[String] = None)(implicit ev: ConfigReader[T]): F[T] = {
    val defaultConfig = ConfigSource.default
    val customConfig = customConf.map(ConfigSource.file).getOrElse(ConfigSource.empty)

    Sync[F].blocking(customConfig.withFallback(defaultConfig).loadOrThrow[T])
  }

  implicit val secretReader: ConfigReader[Secret[String]] = ConfigReader[String].map(Secret(_))

  implicit val pathReader: ConfigReader[Path] = ConfigReader[String].map(Path(_))

  implicit val uriReader: ConfigReader[Uri] = ConfigReader[String].emap { s =>
    Uri.fromString(s).leftMap(ex => CannotConvert(s, "URI", ex.getMessage))
  }

  implicit val hostReader: ConfigReader[Host] =
    ConfigReader[String].emap(s => Host.fromString(s).toRight(CannotConvert(s, "Host", "Parse resulted in None")))

  implicit val portReader: ConfigReader[Port] =
    ConfigReader[Int].emap(i => Port.fromInt(i).toRight(CannotConvert(i.toString, "Port", "Parse resulted in None")))

  implicit val peerIdReader: ConfigReader[PeerId] = ConfigReader[String].map(s => PeerId(Hex(s)))

  implicit val addressReader: ConfigReader[Address] = ConfigReader[String].emap { s =>
    refineV[DAGAddressRefined].apply[String](s) match {
      case Right(value) => Right(Address(value))
      case Left(err)    => Left(CannotConvert(s, "DAGAddressRefined", err))
    }
  }

  implicit val p2pContextReader: ConfigReader[P2PContext] = deriveReader

  implicit val clientConfigReader: ConfigReader[HttpClientConfig] = deriveReader

  implicit val serverConfigReader: ConfigReader[HttpServerConfig] = deriveReader

  implicit val nodeConfigKeyReader: ConfigReader[ApplicationConfig.NodeConfig.Key] = deriveReader

  implicit val nodeConfigPortReader: ConfigReader[ApplicationConfig.NodeConfig.TessellationPorts] = deriveReader

  implicit val nodeConfigNetworkReader: ConfigReader[ApplicationConfig.NodeConfig.Network] = deriveReader

  implicit val nodeConfigReader: ConfigReader[ApplicationConfig.NodeConfig] = deriveReader

  implicit val amazonS3Reader: ConfigReader[ApplicationConfig.AmazonWebServicesConfig.S3] = deriveReader

  implicit val amazonAthenaReader: ConfigReader[ApplicationConfig.AmazonWebServicesConfig.Athena] = deriveReader

  implicit val amazonCredentialsReader: ConfigReader[ApplicationConfig.AmazonWebServicesConfig.Credentials] =
    deriveReader

  implicit val amazonConfigReader: ConfigReader[ApplicationConfig.AmazonWebServicesConfig] = deriveReader

  implicit val baseApplicationConfigReader: ConfigReader[BaseApplicationConfig] = deriveReader

  implicit val applicationConfigReader: ConfigReader[ApplicationConfig] = baseApplicationConfigReader.map(identity)

  implicit val productHint: ProductHint[TessellationPorts] =
    ProductHint[TessellationPorts](ConfigFieldMapping(CamelCase, CamelCase))

  implicit val dataL1ProductHint: ProductHint[AmazonWebServicesConfig] =
    ProductHint[AmazonWebServicesConfig](ConfigFieldMapping(CamelCase, CamelCase))
}
