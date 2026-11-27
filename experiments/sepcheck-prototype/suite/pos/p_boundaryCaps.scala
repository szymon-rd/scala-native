import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful {
  var n = 0
  def get(): String = name
  update def bump(): Unit = n += 1
}
def free(consume r: Res^): Unit = ()
def withRes[T](f: Res^ => T): T = f(Res("s"))
import scala.util.boundary, boundary.break
def t(io: Res^): () ->{io.rd} String = boundary { break(() => io.get()) }
def u(xs: List[Int], io: Res^): () ->{io.rd} String = boundary { for (x <- xs) if (x > 1) break(() => io.get() + x); () => "" }
def w(): String = withRes { a => boundary { if (a.get().isEmpty) break("e"); a.get() } }
