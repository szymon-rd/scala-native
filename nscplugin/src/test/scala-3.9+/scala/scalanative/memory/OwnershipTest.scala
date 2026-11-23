package scala.scalanative.memory

import org.junit.Assert._
import org.junit.Test

import scala.scalanative.NIRCompiler
import scala.scalanative.api.CompilationFailedException

class OwnershipTest {
  private val prelude =
    """|import scala.language.experimental.captureChecking
       |import scala.language.experimental.separationChecking
       |import scala.scalanative.memory.Owned
       |
       |class Res(val name: String) extends Owned {
       |  def use(): Int = name.length
       |}
       |class Box(val borrowed: Res^) extends Owned
       |class OwningBox(consume val owned: Res^) extends Owned
       |def take(consume r: Res^): Unit = ()
       |""".stripMargin

  private def compiles(source: String): Unit =
    try NIRCompiler(_.compile(prelude + source))
    catch {
      case ex: CompilationFailedException =>
        fail(s"Failed to compile source: $ex")
    }

  private def failsWith(source: String, expected: String*): Unit = {
    val err = assertThrows(
      classOf[CompilationFailedException],
      () => NIRCompiler(_.compile(prelude + source))
    )
    expected.foreach { msg =>
      assertTrue(
        s"Expected `$msg` in: ${err.getMessage}",
        err.getMessage.contains(msg)
      )
    }
  }

  @Test def ownershipIsTracked(): Unit = compiles(
    """|def make(name: String): Res^ = Res(name)
       |def test(flag: Boolean): Int = {
       |  val a = Res("a")
       |  val b = make("b")
       |  if (flag) take(a)
       |  var c: Res^ = Res("c")
       |  c = Res("d")
       |  val box = OwningBox(Res("e"))
       |  Owned.drop(b)
       |  Res("f").use() + c.use() + box.owned.use()
       |}
       |""".stripMargin
  )

  @Test def borrowsThatDoNotEscape(): Unit = compiles(
    """|def test(): Int = {
       |  val a = Res("a")
       |  val box = Box(a)
       |  val f = () => a.use()
       |  f() + box.borrowed.use()
       |}
       |""".stripMargin
  )

  @Test def useAfterMove(): Unit = failsWith(
    """|def test(): Int = {
       |  val a = Res("a")
       |  take(a)
       |  a.use()
       |}
       |""".stripMargin,
    "Separation failure",
    "Illegal access to a"
  )

  @Test def closureEscapesOwner(): Unit = failsWith(
    """|def test(): () => Int = {
       |  val a = Res("a")
       |  () => a.use()
       |}
       |""".stripMargin,
    "`a` does not live long enough: it is borrowed by a value that is returned from test"
  )

  // Rejected by the separation checker already: `a` is hidden by `Box(a)`.
  @Test def borrowMovedAway(): Unit = failsWith(
    """|def take(consume b: Box^): Unit = ()
       |def test(): Unit = {
       |  val a = Res("a")
       |  take(Box(a))
       |}
       |""".stripMargin,
    "Separation failure"
  )

  @Test def borrowStoredInOutlivingVar(): Unit = failsWith(
    """|def test(): Int = {
       |  var keep: () => Int = () => 0
       |  {
       |    val a = Res("a")
       |    keep = () => a.use()
       |  }
       |  keep()
       |}
       |""".stripMargin,
    "`a` does not live long enough: it is assigned to keep, which outlives it"
  )

  @Test def temporaryBorrowEscapes(): Unit = failsWith(
    """|def test(): Box^ = Box(Res("a"))
       |""".stripMargin,
    "a temporary owned value does not live long enough"
  )

  @Test def explicitDestructorCall(): Unit = failsWith(
    """|def test(): Unit = {
       |  val a = Res("a")
       |  a.drop()
       |}
       |""".stripMargin,
    "The destructor drop() cannot be called explicitly"
  )

  @Test def moveOutOfOwningValField(): Unit = failsWith(
    """|def test(consume box: OwningBox^): Unit = take(box.owned)
       |""".stripMargin,
    "Cannot move out of owning field owned"
  )

  @Test def moveOutOfBorrowedField(): Unit = failsWith(
    """|def test(consume box: Box^): Unit = take(box.borrowed)
       |""".stripMargin,
    "Cannot move a borrowed value"
  )

  @Test def ownedInSafeZone(): Unit = failsWith(
    """|import scala.scalanative.memory.SafeZone
       |import scala.scalanative.runtime.SafeZoneAllocator.allocate
       |def test(): Unit = SafeZone { sz ?=>
       |  allocate(sz, new Res("a"))
       |  ()
       |}
       |""".stripMargin,
    "Instances of classes extending Owned cannot be allocated in a SafeZone"
  )

  @Test def requiresSeparationChecking(): Unit = {
    val err = assertThrows(
      classOf[CompilationFailedException],
      () =>
        NIRCompiler(
          _.compile(
            """|import scala.scalanative.memory.Owned
               |class Res extends Owned
               |def test(): Unit = new Res()
               |""".stripMargin
          )
        )
    )
    assertTrue(
      err.getMessage,
      err.getMessage.contains(
        "instances of Owned classes can only be created in code compiled with"
      )
    )
  }
}
