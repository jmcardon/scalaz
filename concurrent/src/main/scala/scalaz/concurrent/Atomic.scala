package scalaz
package concurrent

import effect._

import java.util.concurrent.atomic.AtomicReference

trait Atomic[A] {
  def compareAndSet(expected: A, newValue: A): IO[Boolean]
  def get: IO[A]
  def getAndSet(a: A): IO[A]
  def set(a: => A): IO[Unit]

  def update(f: A => A): IO[A] = get flatMap { a =>
    val b = f(a)
    compareAndSet(a, b) flatMap { s =>
      if (s) IO.sync(b)
      else update(f)
    }
  }
}

object Atomic extends Atomics

trait Atomics {
  def newAtomic[A](a: A): IO[Atomic[A]] = IO.sync(new Atomic[A] {
    val value = new AtomicReference(a)

    def compareAndSet(expected: A, newValue: A) = IO.sync(value.compareAndSet(expected, newValue))
    def get = IO.sync(value.get)
    def getAndSet(a: A) = IO.sync(value.getAndSet(a))
    def set(a: => A) = IO.sync(value.set(a))
  })
}
