import language.experimental.captureChecking
import language.experimental.separationChecking
import caps.unsafe.unsafeAssumePure
import ownership.{Droppable, Rt}

final class Res(val name: String) extends caps.ExclusiveCapability, caps.Stateful, Droppable {
  private def check(): Unit = if (Rt.isFreed(this.unsafeAssumePure)) Rt.log += s"USE AFTER FREE $name"
  def get(): String = { check(); name }
  def onDrop(): Unit = Rt.log += s"drop $name"
}
class Peek(val r: Res^) extends caps.ExclusiveCapability, caps.Stateful, Droppable {
  def onDrop(): Unit = Rt.log += s"drop peek(${r.get()})"
}

def take(consume r: Res^): Unit = Rt.log += s"took ${r.get()}"
def make(n: String): Res^ = { val r = Res(n); r }
def pick(flag: Boolean): Res^ = { val a = Res("a"); val b = Res("b"); if (flag) a else b }
def leakClosure(): () => String = { val a = Res("closure"); () => a.get() }

def scope(): Unit = { val a = Res("a"); val b = Res("b"); Rt.log += s"use ${a.get()}${b.get()}" }
def moves(): Unit = { val a = Res("m"); take(a); Rt.log += "after take" }
def conditional(flag: Boolean): Unit = { val a = Res(s"c$flag"); if (flag) take(a); Rt.log += "end" }
def returned(): Unit = { val r = make("r"); Rt.log += s"made ${r.get()}" }
def picked(flag: Boolean): Unit = { val r = pick(flag); Rt.log += s"picked ${r.get()}" }
def explicitMove(): Unit = { val a = Res("x"); val b: Res^ = a; Rt.log += s"moved ${b.get()}" }
def alias(): Unit = { val a = Res("al"); val c = a; Rt.log += s"alias ${c.get()}" }
def exception(): Unit =
  try { val a = Res("e"); if (a.get().nonEmpty) throw new RuntimeException("boom") }
  catch { case _: RuntimeException => Rt.log += "caught" }
def closure(): Unit = { val f = leakClosure(); Rt.log += s"closure ${f()}" }
def borrower(): Unit = { val a = Res("pa"); val p = Peek(a); Rt.log += s"peek ${p.r.get()}" }
def loop(): Unit = { var i = 0; while (i < 2) { val n = Res(s"l$i"); Rt.log += s"iter ${n.get()}"; i += 1 } }

object Main {
  def run(name: String)(body: => Unit): Unit = {
    Rt.log.clear(); body; println(s"$name: ${Rt.log.mkString(", ")}")
  }
  def main(args: Array[String]): Unit = {
    run("scope")(scope())
    run("moves")(moves())
    run("conditional true")(conditional(true))
    run("conditional false")(conditional(false))
    run("returned")(returned())
    run("picked true")(picked(true))
    run("picked false")(picked(false))
    run("explicitMove")(explicitMove())
    run("alias")(alias())
    run("exception")(exception())
    run("closure")(closure())
    run("borrower")(borrower())
    run("loop")(loop())
    println(s"frees=${Rt.frees}")
  }
}
