package scalaz
package effect

import IO._

/**
  * The entry point for a purely-functional application on the JVM.
  *
  * {{{
  * import scalaz.effect.{IO, SafeApp}
  * import scalaz.effect.console._
  *
  * object MyApp extends SafeApp {
  *   def run(args: List[String]): IO[Unit] =
  *     for {
  *       _ <- putStrLn("Hello! What is your name?")
  *       n <- getStrLn
  *       _ <- putStrLn("Hello, " + n + ", good to meet you!")
  *     } yield ()
  * }
  * }}}
  */
trait SafeApp {

  def run(args: ImmutableArray[String]): IO[Unit] = runl(args.toList)

  def runl(args: List[String]): IO[Unit] = runc

  def runc: IO[Unit] = unit

  final def main(args: Array[String]): Unit = {
    run(ImmutableArray.fromArray(args)).unsafePerformIO()
  }

}
