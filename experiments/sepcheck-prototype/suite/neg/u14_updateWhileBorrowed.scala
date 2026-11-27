import language.experimental.captureChecking
import language.experimental.separationChecking
final class Res(val name: String) extends caps.Mutable {
  def get(): String = name
}
final class Box(init: String) extends caps.Mutable {
  private var cur: Res^ = Res(init)
  def peek(): Res^{this.rd} = cur
  update def reset(n: String): Unit = cur = Res(n)
}
def t(b: Box^): String = { val e = b.peek(); b.reset("x"); e.get() } // error
