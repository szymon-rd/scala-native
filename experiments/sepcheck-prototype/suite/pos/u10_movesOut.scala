import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful {
  var n = 0
  def get(): String = name
  update def bump(): Unit = n += 1
}
def free(consume r: Res^): Unit = ()
def withRes[T](f: Res^ => T): T = f(Res("s"))
class Peek(val r: Res^) extends caps.ExclusiveCapability, caps.Stateful
def mk3(): Res^ = { val a = Res("a"); a }
def mk4(): Int = { val a = Res("a"); a.get().length }
def mk5(flag: Boolean): Res^ = { val a = Res("a"); val b = Res("b"); if (flag) a else b }
