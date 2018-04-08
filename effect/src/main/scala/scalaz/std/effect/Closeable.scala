package scalaz
package std.effect

import java.io.Closeable

import scalaz.effect.Resource

trait CloseableInstances {
  implicit def closeableResource[A <: Closeable]: Resource[A] =
    Resource.resourceFromCloseable
}

object closeable extends CloseableInstances
