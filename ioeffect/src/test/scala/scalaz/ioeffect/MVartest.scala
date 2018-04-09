package scalaz.ioeffect

import java.util.concurrent.Executors

import org.specs2.Specification
import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.AroundTimeout
import org.specs2.specification.core.SpecStructure

import scala.concurrent.ExecutionContext

class MVartest(implicit ee: ExecutionEnv)
    extends Specification
    with AroundTimeout {
  def is: SpecStructure =
    s2"""
         MVar Specification
           have deterministic sequential take/put behaviour   $sequentialMVarTest
      """

  def sequentialMVarTest = {

    val ioAction = for {
      ec <- IO.sync(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2)))
      mvar <- MVar.newEmptyMVar[Int](ec)
      _ <- mvar.put(1)
      _ <- mvar.put(2)
      a <- mvar.take
      b <- mvar.take
      a <- IO.point(1)
      b <- IO.point(2)
      _ <- IO.sync(ec.shutdown())
    } yield (a -> b)


    ioAction.unsafePerformIO() must_== (1 -> 2)
  }

}
