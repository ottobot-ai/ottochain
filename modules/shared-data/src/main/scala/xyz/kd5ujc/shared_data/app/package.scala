package xyz.kd5ujc.shared_data

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.flatMap._

import io.constellationnetwork.env.env.{KeyAlias, Password, StorePath}
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.security.SecurityProvider

package object app {

  def loadKeyPair[F[_]: Async: SecurityProvider](config: ApplicationConfig): F[KeyPair] =
    Async[F].fromOption(config.nodeOpt, new Exception("No parameters specified for laoding a keypair")).flatMap {
      nodeConf =>
        val keyStore = StorePath(nodeConf.key.path)
        val alias = KeyAlias(nodeConf.key.alias)
        val password = Password(nodeConf.key.password)

        loadKeyPair(keyStore, alias, password)
    }

  def loadKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias:    KeyAlias,
    password: Password
  ): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        keyStore.value.toString,
        alias.value.value,
        password.value.value.toCharArray,
        password.value.value.toCharArray
      )
}
