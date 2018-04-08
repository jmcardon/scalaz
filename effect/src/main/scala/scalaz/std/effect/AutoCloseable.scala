package scalaz
package std.effect

import scalaz.effect.Resource

trait AutoCloseableInstances {
  implicit def autoCloseableResource[A <: java.lang.AutoCloseable]: Resource[A] =
    Resource.resourceFromAutoCloseable
}

object autoCloseable extends AutoCloseableInstances
