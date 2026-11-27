import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful {
  var n = 0
  def get(): String = name
  update def bump(): Unit = n += 1
}
def free(consume r: Res^): Unit = ()
def withRes[T](f: Res^ => T): T = f(Res("s"))
def w(): Int = withRes { a => List(1, 2).map(i => i + a.get().length).sum }
def v(): String = withRes { a => val f = () => a.get(); f() }
