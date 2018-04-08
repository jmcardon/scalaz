package scalaz
package effect

import IvoryTower._
import RegionT._
import RefCountedFinalizer._
import FinalizerHandle._
import ST._
import Kleisli._
import Free._
import scala.annotation.switch
import scala.concurrent.duration.Duration
import std.function._

/**
  * An `IO[A]` ("Eye-Oh of A") is an immutable data structure that describes an
  * effectful action that may throw an exception (`Throwable`), run forever, or
  * produce a single `A` at some point in the future.
  *
  * `IO` values are ordinary immutable values, and may be used like any other
  * values in purely functional code. Because `IO` values just *describe*
  * effects, which must be interpreted by a separate runtime system, they are
  * entirely pure and do not violate referential transparency.
  *
  * `IO` values can efficiently describe the following classes of effects:
  *
  *  * **Pure Values** &mdash; `IO.apply`
  *  * **Synchronous Effects** &mdash; `IO.sync`
  *  * **Asynchronous Effects** &mdash; `IO.async`
  *  * **Concurrent Effects** &mdash; `io.fork`
  *  * **Resource Effects** &mdash; `io.bracket`
  *
  * The concurrency model is based on *fibers*, a user-land lightweight thread,
  * which permit cooperative multitasking, fine-grained interruption, and very
  * high performance with large numbers of concurrently executing fibers.
  *
  * `IO` values compose with other `IO` values in a variety of ways to build
  * complex, rich, interactive applications. See the methods on `IO` for more
  * details about how to compose `IO` values.
  *
  * In order to integrate with Scala, `IO` values must be interpreted into the
  * Scala runtime. This process of interpretation executes the effects described
  * by a given immutable `IO` value. For more information on interpreting `IO`
  * values, see the default interpreter in `RTS` or the safe main function in
  * `SafeApp`.
  */
sealed abstract class IO[A] extends RTS { self =>
  private[effect] def apply(
      rw: Tower[IvoryTower]): Trampoline[(Tower[IvoryTower], A)] =
    Free.liftF(() => (rw, unsafePerformIO(this)))

  import IO._

  /**
    * Runs I/O and performs side-effects. An unsafe operation.
    * Do not call until the end of the universe.
    */
  def unsafePerformIO(): A = unsafePerformIO(self)

  /**
    * Constructs an IO action whose steps may be interleaved with another.
    * An unsafe operation, since it exposes a trampoline that allows one to
    * step through the components of the IO action.
    */
  def unsafeInterleaveIO(): IO[Trampoline[A]] = IO.sync(apply(ivoryTower).map(_._2))

  /**
    * Maps an `IO[A]` into an `IO[B]` by applying the specified `A => B` function
    * to the output of this action. Repeated applications of `map`
    * (`io.map(f1).map(f2)...map(f10000)`) are guaranteed stack safe to a depth
    * of at least 10,000.
    */
  def map[B](f: A => B): IO[B] = (self.tag: @switch) match {
    case IO.Tags.Point =>
      val io = self.asInstanceOf[IO.Point[A]]

      IO.Point(() => f(io.value()))

    case IO.Tags.Strict =>
      val io = self.asInstanceOf[IO.Strict[A]]

      IO.Strict(f(io.value))

    case IO.Tags.SyncEffect =>
      val io = self.asInstanceOf[IO.SyncEffect[A]]

      IO.SyncEffect(() => f(io.effect()))

    case IO.Tags.Fail => self.asInstanceOf[IO[B]]

    case _ => IO.FlatMap(self, (a: A) => IO.Strict(f(a)))
  }

  /**
    * Creates a composite action that represents this action followed by another
    * one that may depend on the value produced by this one.
    *
    * {{{
    * val parsed = readFile("foo.txt").flatMap(file => parseFile(file))
    * }}}
    */
  def flatMap[B](f0: A => IO[B]): IO[B] = (self.tag: @switch) match {
    case IO.Tags.Fail => self.asInstanceOf[IO[B]]
    case _            => IO.FlatMap(self, f0)
  }

  /**
    * Interleaves the steps of this IO action with the steps of another,
    * consuming the results of both with the given function.
    */
  def unsafeZipWith[B, C](iob: IO[B], f: (A, B) => C): IO[C] =
    for {
      a <- self
      b <- iob
    } yield f(a, b)

  /**
    * Interleaves the steps of this IO action with the steps of another,
    * yielding the results of both.
    */
  def unsafeZip[B](iob: IO[B]): IO[(A, B)] = unsafeZipWith(iob, Tuple2[A, B])

  /**
    * Interleaves the steps of this IO action with the steps of another,
    * ignoring the result of this action.
    */
  def unsafeZip_[B](iob: IO[B]): IO[B] = unsafeZipWith(iob, (a: A, b: B) => b)

  /** Lift this action to a given IO-like monad. */
  def liftIO[M[_]](implicit m: MonadIO[M]): M[A] =
    m.liftIO(this)

  /** Executes the handler if an exception is raised. */
  @deprecated("use catchAll", "7.2.blah")
  def except(handler: Throwable => IO[A]): IO[A] = catchAll(handler)

  /**
    * Executes the handler for exceptions that are raised and match the given predicate.
    * Other exceptions are rethrown.
    */
  def catchSome[B](p: Throwable => Option[B], handler: B => IO[A]): IO[A] =
    catchAll(e =>
      p(e) match {
        case Some(z) => handler(z)
        case None    => IO.fail(e)
    })

  /**
    * Returns a disjunction result which is right if no exception was raised, or left if an
    * exception was raised.
    */
  @deprecated("use attempt", "7.2.blah")
  def catchLeft: IO[Throwable \/ A] = attempt

  /**Like "catchLeft" but takes a predicate to select which exceptions are caught. */
  def catchSomeLeft[B](p: Throwable => Option[B]): IO[B \/ A] =
    attempt.flatMap[B \/ A] {
      case \/-(r) => IO.now(\/-(r))
      case -\/(l) =>
        p(l) match {
          case Some(e) => IO.now(-\/(e))
          case None    => IO.fail(l)
        }
    }

  /**Like "finally", but only performs the final action if there was an exception. */
  def onException[B](action: IO[B]): IO[A] =
    self.catchAll(e =>
      for {
        _ <- action
        a <- IO.fail[A](e)
      } yield a)

  /**
    * Applies the "during" action, calling "after" regardless of whether there was an exception.
    * All exceptions are rethrown. Generalizes try/finally.
    */
  def bracket[B, C](after: A => IO[B])(during: A => IO[C]): IO[C] =
    IO.Bracket(this, (_: BracketResult[C], a: A) => after(a).toUnit, during)

  /**
    * When this action represents acquisition of a resource (for example,
    * opening a file, launching a thread, etc.), `bracket` can be used to ensure
    * the acquisition is not interrupted and the resource is released.
    *
    * The function does two things:
    *
    * 1. Ensures this action, which acquires the resource, will not be
    * interrupted. Of course, acquisition may fail for internal reasons (an
    * uncaught exception).
    * 2. Ensures the `release` action will not be interrupted, and will be
    * executed so long as this action successfully acquires the resource.
    *
    * In between acquisition and release of the resource, the `use` action is
    * executed.
    *
    * If the `release` action fails, then the entire computation will fail even
    * if the `use` action succeeds. If this fail-fast behavior is not desired,
    * errors produced by the `release` action can be caught and ignored.
    *
    * {{{
    * openFile("data.json").bracket(closeFile) { file =>
    *   for {
    *     header <- readHeader(file)
    *     ...
    *   } yield result
    * }
    * }}}
    */
  final def bracket8[B](release: A => IO[Unit])(use: A => IO[B]): IO[B] =
    IO.Bracket(this, (_: BracketResult[B], a: A) => release(a), use)

  /**
    * A more powerful version of `bracket` that provides information on whether
    * or not `use` succeeded to the release action.
    */
  final def bracket0[B](release: (BracketResult[B], A) => IO[Unit])(
      use: A => IO[B]): IO[B] =
    IO.Bracket(this, release, use)

  /**
    * A less powerful variant of `bracket` where the value produced by this
    * action is not needed.
    */
  final def bracket8_[B](release: IO[Unit])(use: IO[B]): IO[B] =
    self.bracket(_ => release)(_ => use)

  /**A variant of "bracket" where the return value of this computation is not needed. */
  def bracket_[B, C](after: IO[B])(during: IO[C]): IO[C] =
    bracket(_ => after)(_ => during)

  /**A variant of "bracket" that performs the final action only if there was an error. */
  def bracketOnError[B, C](after: A => IO[B])(during: A => IO[C]): IO[C] =
    bracket0(
      (r: BracketResult[C], a: A) =>
        r match {
          case BracketResult.Failed(_)      => after(a).toUnit
          case BracketResult.Interrupted(_) => after(a).toUnit
          case _                            => IO.unit
      }
    )(during)

  def bracketIO[M[_], B](after: A => IO[Unit])(during: A => M[B])(
      implicit m: MonadControlIO[M]): M[B] =
    controlIO((runInIO: RunInBase[M, IO]) =>
      bracket(after)(runInIO.apply compose during))

  /** An automatic resource management. */
  def using[C](f: A => IO[C])(implicit resource: Resource[A]): IO[C] =
    bracket(resource.close)(f)

  /**
    * Executes the release action only if there was an error.
    */
  final def bracketOnError8[B](release: A => IO[Unit])(use: A => IO[B]): IO[B] =
    bracket0(
      (r: BracketResult[B], a: A) =>
        r match {
          case BracketResult.Failed(_)      => release(a)
          case BracketResult.Interrupted(_) => release(a)
          case _                            => IO.unit
      }
    )(use)

  /**
    * Runs the specified cleanup action if this action errors, providing the
    * error to the cleanup action. The cleanup action will not be interrupted.
    */
  final def onError(cleanup: Throwable => IO[Unit]): IO[A] =
    IO.unit.bracket0(
      (r: BracketResult[A], a: Unit) =>
        r match {
          case BracketResult.Failed(e)      => cleanup(e)
          case BracketResult.Interrupted(e) => cleanup(e)
          case _                            => IO.unit
      }
    )(_ => self)

  /**
    * Forks this action into its own separate fiber, returning immediately
    * without the value produced by this action.
    *
    * The `Fiber[A]` returned by this action can be used to interrupt the forked
    * fiber with some exception, or to join the fiber to "await" its computed
    * value.
    *
    * {{{
    * for {
    *   fiber <- subtask.fork
    *   // Do stuff...
    *   a <- subtask.join
    * } yield a
    * }}}
    */
  final def fork: IO[Fiber[A]] = IO.Fork(this, Option.empty)

  /**
    * A more powerful version of `fork` that allows specifying a handler to be
    * invoked on any exceptions that are not handled by the forked fiber.
    */
  final def fork0(handler: Throwable => IO[Unit]): IO[Fiber[A]] =
    IO.Fork(this, Some(handler))

  /**
    * Executes both this action and the specified action in parallel,
    * returning a tuple of their results. If either individual action fails,
    * then the returned action will fail.
    *
    * TODO: Replace with optimized primitive.
    */
  final def par[B](that: IO[B]): IO[(A, B)] =
    self.attempt.raceWith(that.attempt) {
      case -\/((-\/(e), fiberb)) => fiberb.interrupt(e) *> IO.fail(e)
      case -\/((\/-(a), fiberb)) => IO.absolve(fiberb.join).map(b => (a, b))
      case \/-((-\/(e), fibera)) => fibera.interrupt(e) *> IO.fail(e)
      case \/-((\/-(b), fibera)) => IO.absolve(fibera.join).map(a => (a, b))
    }

  /**
    * Races this action with the specified action, returning the first
    * result to produce an `A`, whichever it is. If neither action succeeds,
    * then the action will be terminated with some error.
    */
  final def race(that: IO[A]): IO[A] = raceWith(that) {
    case -\/((a, fiber)) =>
      fiber.interrupt(Errors.LostRace(\/-(fiber))).const(a)
    case \/-((a, fiber)) =>
      fiber.interrupt(Errors.LostRace(-\/(fiber))).const(a)
  }

  /**
    * Races this action with the specified action, invoking the
    * specified finisher as soon as one value or the other has been computed.
    */
  final def raceWith[B, C](that: IO[B])(
      finish: (A, Fiber[B]) \/ (B, Fiber[A]) => IO[C]): IO[C] =
    IO.Race[A, B, C](self, that, finish)

  /**
    * Executes this action and returns its value, if it succeeds, but
    * otherwise executes the specified action.
    */
  final def orElse(that: => IO[A]): IO[A] =
    self.attempt.flatMap(_.fold(_ => that, IO.now))

  /**
    * Executes this action, capturing both failure and success and returning
    * the result in a `Disjunction`. This method is useful for recovering from
    * `IO` actions that may fail.
    */
  final def attempt: IO[Throwable \/ A] = (self.tag: @switch) match {
    case IO.Tags.Point =>
      val io = self.asInstanceOf[IO.Point[A]]

      IO.Point(() => \/-(io.value()))

    case IO.Tags.Strict =>
      val io = self.asInstanceOf[IO.Strict[A]]

      IO.Strict(\/-(io.value))

    case IO.Tags.Fail =>
      val io = self.asInstanceOf[IO.Fail[A]]

      IO.Strict(-\/(io.failure))

    case _ => IO.Attempt(self)
  }

  /**
    * Executes the specified finalizer, whether this action succeeds, fails, or
    * is interrupted.
    */
  final def ensuring0(finalizer: IO[Unit]): IO[A] =
    IO.unit.bracket(_ => finalizer)(_ => self)

  /**Like "bracket", but takes only a computation to run afterward. Generalizes "finally". */
  def ensuring[B](sequel: IO[B]): IO[A] =
    IO.unit.bracket(_ => sequel.toUnit)(_ => self)

  /**
    * Supervises this action, which ensures that any fibers that are forked
    * by the action are killed with the specified error when this action
    * completes.
    */
  final def supervised(error: Throwable): IO[A] = IO.Supervise(self, error)

  /**
    * Performs this action non-interruptibly. This will prevent the
    * action from being killed externally, but the action may fail
    * for internal reasons (e.g. an uncaught exception).
    */
  final def uninterruptibly: IO[A] = IO.Uninterruptible(self)

  /**
    * Recovers from all errors.
    *
    * {{{
    * openFile("config.json").catchAll(_ => IO.now(defaultConfig))
    * }}}
    */
  final def catchAll(h: Throwable => IO[A]): IO[A] = catchSome {
    case t: Throwable => h(t)
  }

  /**
    * Recovers from some or all of the error cases.
    *
    * {{{
    * openFile("data.json").catchSome {
    *   case FileNotFoundException(_) => openFile("backup.json")
    * }
    * }}}
    */
  final def catchSome(pf: PartialFunction[Throwable, IO[A]]): IO[A] = {
    def tryRescue(t: Throwable): IO[A] =
      if (pf.isDefinedAt(t)) pf(t) else IO.fail(t)

    self.attempt.flatMap(_.fold(tryRescue, IO.now))
  }

  /**
    * Maps this action to the specified constant while preserving the
    * effects of this action.
    */
  final def const[B](b: => B): IO[B] = self.map(_ => b)

  /**
    * A variant of `flatMap` that ignores the value produced by this action.
    */
  final def *>[B](io: => IO[B]): IO[B] = self.flatMap(_ => io)

  /**
    * Sequences the specified action after this action, but ignores the
    * value produced by the action.
    */
  final def <*[B](io: => IO[B]): IO[A] = self.flatMap(io.const(_))

  /**
    * Repeats this action forever (until the first error).
    */
  final def forever[B]: IO[B] = self *> self.forever

  /**
    * Retries continuously until this action succeeds.
    */
  final def retry: IO[A] = self orElse retry

  /**
    * Retries this action the specified number of times, until the first success.
    * Note that the action will always be run at least once, even if `n < 1`.
    */
  final def retryN(n: Int): IO[A] =
    if (n <= 1) self
    else self orElse (retryN(n - 1))

  /**
    * Retries continuously until the action succeeds or the specified duration
    * elapses.
    */
  final def retryFor(duration: Duration): IO[A] =
    IO.absolve(
      retry.attempt race (IO.sleep(duration) *>
        IO.now(-\/(Errors.TimeoutException(duration))))
    )

  /**
    * Maps this action to one producing unit, but preserving the effects of
    * this action.
    */
  final def toUnit: IO[Unit] = const(())

  /**
    * Calls the provided function with the result of this action, and
    * sequences the resulting action after this action, but ignores the
    * value produced by the action.
    *
    * {{{
    * for {
    *   file <- readFile("data.json").peek(putStrLn)
    * } yield file
    * }}}
    */
  final def peek[B](f: A => IO[B]): IO[A] = self.flatMap(a => f(a).const(a))

  /**
    * Times out this action by the specified duration.
    *
    * {{{
    * action.timeout(1.second)
    * }}}
    */
  final def timeout(duration: Duration): IO[A] = {
    val err: IO[Throwable \/ A] = IO.now(-\/(Errors.TimeoutException(duration)))

    IO.absolve(self.attempt.race(err.delay(duration)))
  }

  /**
    * Delays this action by the specified amount of time.
    */
  final def delay(duration: Duration): IO[A] =
    IO.sleep(duration) *> self

  /**
    * An integer that identifies the term in the `IO` sum type to which this
    * instance belongs (e.g. `IO.Tags.Point`).
    */
  def tag: Int = IO.Tags.Fail //Necessary since MiMa :(

}

sealed abstract class IOInstances1 {
  implicit def IOSemigroup[A](implicit A: Semigroup[A]): Semigroup[IO[A]] =
    Semigroup.liftSemigroup[IO, A](IO.ioMonad, A)

  implicit val iOLiftIO: LiftIO[IO] = new IOLiftIO {}

  implicit val ioMonad: Monad[IO] with BindRec[IO] = new IOMonad {}
}

sealed abstract class IOInstances0 extends IOInstances1 {
  implicit def IOMonoid[A](implicit A: Monoid[A]): Monoid[IO[A]] =
    Monoid.liftMonoid[IO, A](ioMonad, A)

  implicit val ioMonadIO: MonadIO[IO] = new MonadIO[IO] with IOLiftIO
  with IOMonad
}

sealed abstract class IOInstances extends IOInstances0 {
  implicit val ioMonadCatchIO: MonadCatchIO[IO] = new IOMonadCatchIO
  with IOLiftIO with IOMonad

  implicit val ioCatchable: Catchable[IO] =
    new Catchable[IO] {
      def attempt[A](f: IO[A]): IO[Throwable \/ A] = f.attempt
      def fail[A](err: Throwable): IO[A] = IO.fail(err)
    }

}

private trait IOMonad extends Monad[IO] with BindRec[IO] {
  def point[A](a: => A): IO[A] = IO.point(a)
  override def map[A, B](fa: IO[A])(f: A => B) = fa map f
  def bind[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa flatMap f
  def tailrecM[A, B](f: A => IO[A \/ B])(a: A): IO[B] = IO.tailrecM(f)(a)
}

private trait IOLiftIO extends LiftIO[IO] {
  def liftIO[A](ioa: IO[A]) = ioa
}

private trait IOMonadCatchIO extends MonadCatchIO[IO] {
  def except[A](io: IO[A])(h: Throwable => IO[A]): IO[A] = io.catchAll(h)
}

object IO extends IOInstances {
  def apply[A](a: => A): IO[A] =
    sync(a)

  /** Reads a character from standard input. */
  def getChar: IO[Char] = IO.sync {
    val s = scala.Console.in.readLine()
    if (s == null)
      throw new java.io.EOFException("Console has reached end of input")
    else
      s charAt 0
  }

  /** Writes a character to standard output. */
  def putChar(c: Char): IO[Unit] =
    sync(print(c))

  /** Writes a string to standard output. */
  def putStr(s: String): IO[Unit] =
    sync(print(s))

  /** Writes a string to standard output, followed by a newline.*/
  def putStrLn(s: String): IO[Unit] =
    sync(println(s))

  /** Reads a line of standard input. */
  def readLn: IO[String] = sync(scala.Console.in.readLine())

  def put[A](a: A)(implicit S: Show[A]): IO[Unit] =
    sync(print(S shows a))

  def putLn[A](a: A)(implicit S: Show[A]): IO[Unit] =
    sync(println(S shows a))

  type RunInBase[M[_], Base[_]] =
    Forall[λ[α => M[α] => Base[M[α]]]]

  import scalaz.Isomorphism.<~>

  /** Hoist RunInBase given a natural isomorphism between the two functors */
  def hoistRunInBase[F[_], G[_]](r: RunInBase[G, IO])(
      implicit iso: F <~> G): RunInBase[F, IO] =
    new RunInBase[F, IO] {
      def apply[B] = (x: F[B]) => r.apply(iso.to(x)).map(iso.from(_))
    }

  /** Construct an IO action from a world-transition function. */
  @deprecated("for the love of god do not use this", "blah")
  def io[A](f: Tower[IvoryTower] => Trampoline[(Tower[IvoryTower], A)]): IO[A] =
    sync(f(IvoryTower.ivoryTower).extractF.apply()._2)

  // Mutable variables in the IO monad
  def newIORef[A](a: => A): IO[IORef[A]] =
    STToIO(newVar(a)) flatMap (v => sync(IORef.ioRef(v)))

  /**Throw the given error in the IO monad. */
  def throwIO[A](e: Throwable): IO[A] = IO.fail(e)

  def idLiftControl[M[_], A](f: RunInBase[M, M] => M[A])(
      implicit m: Monad[M]): M[A] =
    f(new RunInBase[M, M] {
      def apply[B] = (x: M[B]) => m.point(x)
    })

  def controlIO[M[_], A](f: RunInBase[M, IO] => IO[M[A]])(
      implicit M: MonadControlIO[M]): M[A] =
    M.join(M.liftControlIO(f))

  /**
    * Register a finalizer in the current region. When the region terminates,
    * all registered finalizers will be performed if they're not duplicated to a parent region.
    */
  def onExit[S, P[_]: MonadIO](
      finalizer: IO[Unit]): RegionT[S, P, FinalizerHandle[RegionT[S, P, ?]]] =
    regionT(kleisli(hsIORef =>
      (for {
        refCntIORef <- newIORef(1)
        h = refCountedFinalizer(finalizer, refCntIORef)
        _ <- hsIORef.mod(h :: _)
      } yield finalizerHandle[RegionT[S, P, ?]](h)).liftIO[P]))

  /**
    * Execute a region inside its parent region P. All resources which have been opened in the given
    * region and which haven't been duplicated using "dup", will be closed on exit from this function
    * whether by normal termination or by raising an exception.
    * Also all resources which have been duplicated to this region from a child region are closed
    * on exit if they haven't been duplicated themselves.
    * The Forall quantifier prevents resources from being returned by this function.
    */
  def runRegionT[P[_]: MonadControlIO, A](r: Forall[RegionT[?, P, A]]): P[A] = {
    def after(hsIORef: IORef[List[RefCountedFinalizer]]) =
      for {
        hs <- hsIORef.read
        _ <- hs.foldRight[IO[Unit]](IO.unit) {
          case (r, o) =>
            for {
              refCnt <- r.refcount.mod(_ - 1)
              _ <- if (refCnt == 0) r.finalizer else IO.unit
            } yield ()
        }
      } yield ()
    newIORef(List[RefCountedFinalizer]()).bracketIO(after)(s =>
      r.apply.value.run(s))
  }

  def tailrecM[A, B](f: A => IO[A \/ B])(a: A): IO[B] =
    f(a).flatMap {
      case \/-(r) => IO.now(r)
      case -\/(l) => tailrecM[A, B](f)(l)
    }

  /** An IO action is an ST action. */
  implicit def IOToST[A](io: IO[A]): ST[IvoryTower, A] =
    st(io(_).run)

  /** An IO action that does nothing. */
  @deprecated("use unit", "blah")
  val ioUnit: IO[Unit] =
    now(())

  // IO2
  object Tags {
    final val FlatMap = 0
    final val Point = 1
    final val Strict = 2
    final val SyncEffect = 3
    final val Fail = 4
    final val AsyncEffect = 5
    final val Attempt = 6
    final val Fork = 7
    final val Race = 8
    final val Suspend = 9
    final val Bracket = 10
    final val Uninterruptible = 11
    final val Sleep = 12
    final val Supervise = 13
  }
  final case class FlatMap[A0, A](io: IO[A0], flatMapper: A0 => IO[A])
      extends IO[A] {
    override final def tag = Tags.FlatMap
  }

  final case class Point[A](value: () => A) extends IO[A] {
    override final def tag = Tags.Point
  }

  final case class Strict[A](value: A) extends IO[A] {
    override final def tag = Tags.Strict
  }

  final case class SyncEffect[A](effect: () => A) extends IO[A] {
    override final def tag = Tags.SyncEffect
  }

  final case class Fail[A](failure: Throwable) extends IO[A] {
    override final def tag = Tags.Fail
  }

  final case class AsyncEffect[A](
      register: (Throwable \/ A => Unit) => AsyncReturn[A])
      extends IO[A] {
    override final def tag = Tags.AsyncEffect
  }

  final case class Attempt[A](value: IO[A]) extends IO[Throwable \/ A] {
    override final def tag = Tags.Attempt
  }

  final case class Fork[A](value: IO[A], handler: Option[Throwable => IO[Unit]])
      extends IO[Fiber[A]] {
    override final def tag = Tags.Fork
  }

  final case class Race[A0, A1, A](
      left: IO[A0],
      right: IO[A1],
      finish: (A0, Fiber[A1]) \/ (A1, Fiber[A0]) => IO[A])
      extends IO[A] {
    override final def tag = Tags.Race
  }

  final case class Suspend[A](value: () => IO[A]) extends IO[A] {
    override final def tag = Tags.Suspend
  }

  final case class Bracket[A, B](acquire: IO[A],
                                 release: (BracketResult[B], A) => IO[Unit],
                                 use: A => IO[B])
      extends IO[B] {
    override final def tag = Tags.Bracket
  }

  final case class Uninterruptible[A](io: IO[A]) extends IO[A] {
    override final def tag = Tags.Uninterruptible
  }

  final case class Sleep(duration: Duration) extends IO[Unit] {
    override final def tag = Tags.Sleep
  }

  final case class Supervise[A](value: IO[A], error: Throwable) extends IO[A] {
    override final def tag = Tags.Supervise
  }

  /**
    * Lifts a strictly evaluated value into the `IO` monad.
    */
  final def now[A](a: A): IO[A] = Strict(a)

  /**
    * Lifts a non-strictly evaluated value into the `IO` monad. Do not use this
    * function to capture effectful code. The result is undefined but may
    * include runtime errors.
    */
  final def point[A](a: => A): IO[A] = Point(() => a)

  /**
    * Raises the specified error. The moral equivalent of `throw` for pure code.
    */
  final def fail[A](t: Throwable): IO[A] = Fail(t)

  /**
    * Strictly-evaluated unit lifted into the `IO` monad.
    */
  final val unit: IO[Unit] = now(())

  /**
    * Sleeps for the specified duration. This is always asynchronous.
    */
  final def sleep(duration: Duration): IO[Unit] = Sleep(duration)

  /**
    * Supervises the specified action, which ensures that any actions directly
    * forked by the action are killed with the specified error upon the action's
    * own termination.
    */
  final def supervise[A](io: IO[A], error: Throwable): IO[A] =
    Supervise(io, error)

  /**
    * Flattens a nested action.
    */
  final def flatten[A](io: IO[IO[A]]): IO[A] = io.flatMap(a => a)

  /**
    * Lazily produces an `IO` value whose construction may have actional costs
    * that should be be deferred until evaluation.
    *
    * Do not use this method to effectfully construct `IO` values. The results
    * will be undefined and most likely involve the physical explosion of your
    * computer in a heap of rubble.
    */
  final def suspend[A](io: => IO[A]): IO[A] = Suspend(() => io)

  /**
    * Imports a synchronous effect into a pure `IO` value. If the thunk returns
    * a null value, this will throw a `NullPointerException` inside `IO`.
    *
    * {{{
    * def putStrLn(line: String): IO[Unit] = IO.sync(println(line))
    * }}}
    */
  final def sync[A](effect: => A): IO[A] = SyncEffect(() => effect)

  /**
    * Imports an asynchronous effect into a pure `IO` value. See `async0` for
    * the more expressive variant of this function.
    */
  final def async[A](register: (Throwable \/ A => Unit) => Unit): IO[A] =
    AsyncEffect { callback =>
      register(callback)

      AsyncReturn.later[A]
    }

  /**
    * Imports an asynchronous effect into a pure `IO` value. The effect has the
    * option of returning the value synchronously, which is useful in cases
    * where it cannot be determined if the effect is synchronous or asynchronous
    * until the effect is actually executed. The effect also has the option of
    * returning a canceler, which will be used by the runtime to cancel the
    * asynchronous effect if the fiber executing the effect is interrupted.
    */
  final def async0[A](
      register: (Throwable \/ A => Unit) => AsyncReturn[A]): IO[A] =
    AsyncEffect(register)

  /**
    * Returns a computation that will never produce anything. The moral
    * equivalent of `while(true) {}`, only without the wasted CPU cycles.
    */
  final def never[A]: IO[A] = Never.asInstanceOf[IO[A]]

  /**
    * Submerges the error case of a disjunction into the `IO`. The inverse
    * operation of `IO.attempt`.
    */
  final def absolve[A](v: IO[Throwable \/ A]): IO[A] =
    v.flatMap {
      case -\/(e) => IO.fail(e)
      case \/-(a) => IO.now(a)
    }

  /**
    * Requires that the given `IO[Maybe[A]]` contain a value. If there is no
    * value, then the specified error will be raised.
    */
  final def require[A](t: Throwable): IO[Option[A]] => IO[A] =
    (io: IO[Option[A]]) => io.flatMap(_.fold(IO.fail[A](t))(IO.now[A]))

  private final val Never
    : IO[Any] = IO.async[Any] { (k: (Throwable \/ Any) => Unit) =>
    }
}
