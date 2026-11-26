import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful {
  var n = 0
  def get(): String = name
  update def bump(): Unit = n += 1
}
def free(consume r: Res^): Unit = ()
def withRes[T](f: Res^ => T): T = f(Res("s"))
trait L[X] { def apply(x: Res^): X }
def t(): () -> String = { val l: L[() -> String] = x => () => x.get(); withRes(r => l(r)) } // error
