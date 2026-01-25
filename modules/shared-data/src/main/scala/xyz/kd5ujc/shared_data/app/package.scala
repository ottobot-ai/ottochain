package xyz.kd5ujc.shared_data

import java.security.KeyPair

import cats.effect.Async

import io.constellationnetwork.env.env.{KeyAlias, Password, StorePath}
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.security.SecurityProvider

package object app {

  def loadKeyPair[F[_]: Async: SecurityProvider](config: SharedAppConfig): F[KeyPair] =
    loadKeyPair(
      keyStore = StorePath(config.node.key.path),
      alias = KeyAlias(config.node.key.alias),
      password = Password(config.node.key.password)
    )

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
