import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful {
  var n = 0
  def get(): String = name
  update def bump(): Unit = n += 1
}
def free(consume r: Res^): Unit = ()
def withRes[T](f: Res^ => T): T = f(Res("s"))
class H(consume val r: Res^) extends caps.ExclusiveCapability, caps.Stateful
def freeH(consume h: H^): Unit = ()
def t(consume h: H^): Unit = { free(h.r); freeH(h) } // error
