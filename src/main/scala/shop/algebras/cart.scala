package shop.algebras

import cats.Monad
import cats.implicits._
import dev.profunktor.redis4cats.algebra.RedisCommands
import io.estatico.newtype.ops._
import java.{ util => ju }
import shop.domain.auth._
import shop.domain.brand._
import shop.domain.category._
import shop.domain.cart._
import shop.domain.item._
import LiveShoppingCart._
import scala.concurrent.duration._

trait ShoppingCart[F[_]] {
  def add(userId: UserId, item: ItemId, quantity: Quantity): F[Unit]
  def get(userId: UserId): F[List[CartItem]]
  def delete(userId: UserId): F[Unit]
  def removeItem(userId: UserId, itemId: ItemId): F[Unit]
  def update(userId: UserId, cart: Cart): F[Unit]
}

object LiveShoppingCart {
  def make[F[_]: Monad](
      items: Items[F],
      redis: RedisCommands[F, String, String]
  ): F[ShoppingCart[F]] =
    new LiveShoppingCart(items, redis).pure[F].widen
}

class LiveShoppingCart[F[_]: Monad] private (
    items: Items[F],
    redis: RedisCommands[F, String, String]
) extends ShoppingCart[F] {

  // TODO: Take from config file
  private val Expiration = 30.minutes

  def add(userId: UserId, itemId: ItemId, quantity: Quantity): F[Unit] =
    redis.hSet(userId.value.toString, itemId.value.toString, quantity.value.toString) *>
      redis.expire(userId.value.toString, Expiration)

  def get(userId: UserId): F[List[CartItem]] =
    redis.hGetAll(userId.value.toString).flatMap { it =>
      it.toList
        .traverse {
          case (k, v) =>
            val itemId   = ju.UUID.fromString(k).coerce[ItemId]
            val quantity = v.toInt.coerce[Quantity]
            items
              .findById(itemId)
              .map(_.toList.map(i => CartItem(i, quantity)))
        }
        .map(_.flatten)
    }

  def delete(userId: UserId): F[Unit] =
    redis.del(userId.value.toString)

  def removeItem(userId: UserId, itemId: ItemId): F[Unit] =
    redis.hDel(userId.value.toString, itemId.value.toString)

  def update(userId: UserId, cart: Cart): F[Unit] =
    redis.hGetAll(userId.value.toString).flatMap { it =>
      it.toList.traverse_ {
        case (k, _) =>
          val itemId = ju.UUID.fromString(k).coerce[ItemId]
          cart.items.get(itemId).fold(().pure[F]) { q =>
            redis.hSet(userId.value.toString, k, q.value.toString)
          }
      } *>
        redis.expire(userId.value.toString, Expiration)
    }

}
