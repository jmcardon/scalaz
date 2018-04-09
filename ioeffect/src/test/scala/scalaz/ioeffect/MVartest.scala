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
  def getEc = ExecutionContext.Implicits.global

  def is: SpecStructure =
    s2"""
         MVar Specification
           have deterministic single take/put behavior        $singleMVarEmptyTest
           have deterministic single take/put behavior empty  $singleMVarTest
           have deterministic sequential take/put behaviour   $sequentialMVarTest
      """

  def singleMVarEmptyTest = {
    val ioAction = for {
      mvar <- MVar.newEmptyMVar[Int](getEc)
      _ <- mvar.put(1)
      a <- mvar.read
      b <- mvar.take
    } yield (a -> b)

    ioAction.unsafePerformIO() must_== (1 -> 1)
  }

  def singleMVarTest = {
    val ioAction = for {
      mvar <- MVar.newMVar(1)(getEc)
      a <- mvar.read
      b <- mvar.take
    } yield (a -> b)

    ioAction.unsafePerformIO() must_== (1 -> 1)
  }

  def sequentialMVarTest = {

    val ioAction = for {
      mvar <- MVar.newEmptyMVar[Int](getEc)
      _ <- mvar.put(1).fork
      _ <- mvar.put(2).fork
      a <- mvar.take
      b <- mvar.take
    } yield (a -> b)

    ioAction.unsafePerformIO() must_== (1 -> 2)
  }

}
