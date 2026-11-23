package scala.scalanative.memory

import scala.language.experimental.captureChecking
import scala.language.experimental.separationChecking

import org.junit.Assert._
import org.junit.Test

import scala.scalanative.runtime.OwnedAllocator

object OwnershipTest {
  class Res(val name: String) extends Owned {
    override def drop(): Unit = OwnershipLog(s"drop $name")
    def use(): Int = name.length
  }

  class Node(val id: Int, val payload: Array[Int]) extends Owned {
    override def drop(): Unit = OwnershipLog(s"drop node $id")
    def sum: Int = payload.sum
  }

  class Pair(consume val left: Res^, consume val right: Res^) extends Owned {
    override def drop(): Unit = OwnershipLog("drop pair")
  }

  class Borrowing(val res: Res^) extends Owned {
    override def drop(): Unit = OwnershipLog("drop borrowing")
  }

  class Slot extends Owned {
    var content: Res^ = null
    update def set(consume r: Res^): Unit = content = r
    override def drop(): Unit = OwnershipLog("drop slot")
  }

  class Holder(consume val node: Node^) extends Owned {
    var next: Node^ = null
    update def setNext(consume n: Node^): Unit = next = n
    override def drop(): Unit = OwnershipLog(s"drop holder ${node.id}")
  }

  class SubHolder(consume val extra: Node^)
      extends Holder(Node(50, Array(0))) {
    override def drop(): Unit = OwnershipLog("drop subholder")
  }

  class Closable(val name: String) extends Owned {
    consume def close(): Unit = OwnershipLog(s"close $name")
    override def drop(): Unit = OwnershipLog(s"drop $name")
  }

  def take(consume r: Res^): Unit = OwnershipLog(s"took ${r.name}")

  def make(name: String): Res^ = Res(name)

  def earlyReturn(flag: Boolean): Int = {
    val a = Node(1, Array(1))
    if (flag) return 42
    val b = Node(2, Array(2))
    a.sum + b.sum
  }

  def matching(x: Any): String = {
    val a = Node(3, Array(3))
    x match {
      case i: Int    => s"int $i ${a.id}"
      case s: String => s"string $s"
      case _         => "other"
    }
  }

  def conditional(flag: Boolean): Unit = {
    val a = Res(s"c-$flag")
    if (flag) take(a)
    OwnershipLog("end")
  }
}

class OwnershipTest {
  import OwnershipTest._
  import OwnershipLog.record

  @Test def instancesAreNotOnTheGCHeap(): Unit = {
    val live = Owned.liveCount()
    val events = record {
      val a = Res("a")
      assertTrue(OwnedAllocator.isOwned(a))
      assertEquals(live + 1, Owned.liveCount())
    }
    assertEquals(List("drop a"), events)
    assertEquals(live, Owned.liveCount())
  }

  @Test def dropsInReverseDeclarationOrder(): Unit = assertEquals(
    List("drop c", "drop b", "drop a"),
    record {
      val a = Res("a")
      val b = Res("b")
      val c = Res("c")
      assertEquals(3, a.use() + b.use() + c.use())
    }
  )

  @Test def dropsAtTheEndOfNestedScopes(): Unit = assertEquals(
    List("drop inner", "after block", "drop outer"),
    record {
      val outer = Res("outer")
      locally {
        val inner = Res("inner")
        inner.use()
      }
      OwnershipLog("after block")
    }
  )

  @Test def moveIntoConsumeParameter(): Unit = assertEquals(
    List("took m", "drop m", "after take"),
    record {
      val a = Res("m")
      take(a)
      OwnershipLog("after take")
    }
  )

  @Test def conditionalMoveUsesDropFlag(): Unit = {
    assertEquals(
      List("took c-true", "drop c-true", "end"),
      record(conditional(true))
    )
    assertEquals(List("end", "drop c-false"), record(conditional(false)))
  }

  @Test def explicitEarlyDrop(): Unit = assertEquals(
    List("drop e", "after drop"),
    record {
      val a = Res("e")
      Owned.drop(a)
      OwnershipLog("after drop")
    }
  )

  @Test def consumeMethodOwnsItsReceiver(): Unit = assertEquals(
    List("close f", "drop f", "after close"),
    record {
      val f = Closable("f")
      f.close()
      OwnershipLog("after close")
    }
  )

  @Test def ownershipIsReturned(): Unit = assertEquals(
    List("made r", "drop r"),
    record {
      val r = make("r")
      OwnershipLog(s"made ${r.name}")
    }
  )

  @Test def temporariesAreDropped(): Unit = assertEquals(
    List("drop t2", "after statements", "drop t1"),
    record {
      Res("t1").use()
      make("t2")
      OwnershipLog("after statements")
    }
  )

  @Test def reassignmentDropsPreviousValue(): Unit = assertEquals(
    List("drop v1", "after reassign", "drop v2"),
    record {
      var v: Res^ = Res("v1")
      v = Res("v2")
      OwnershipLog("after reassign")
    }
  )

  @Test def dropGlueDropsOwningFields(): Unit = assertEquals(
    List("built", "drop pair", "drop left", "drop right"),
    record {
      val p = Pair(Res("left"), Res("right"))
      OwnershipLog("built")
    }
  )

  @Test def borrowedFieldsAreNotDropped(): Unit = assertEquals(
    List("drop borrowing", "drop owner"),
    record {
      val owner = Res("owner")
      val b = Borrowing(owner)
      b.res.use()
    }
  )

  @Test def assigningOwningFieldDropsPreviousValue(): Unit = assertEquals(
    List("drop s1", "filled", "drop slot", "drop s2"),
    record {
      val s = Slot()
      s.set(Res("s1"))
      s.set(Res("s2"))
      OwnershipLog("filled")
    }
  )

  @Test def dropGlueOfSuperclasses(): Unit = assertEquals(
    List(
      "built",
      "drop subholder",
      "drop node 51",
      "drop node 50",
      "drop node 52"
    ),
    record {
      val s = SubHolder(Node(51, Array(0)))
      s.setNext(Node(52, Array(0)))
      OwnershipLog("built")
    }
  )

  @Test def dropsWhenExceptionIsThrown(): Unit = assertEquals(
    List("drop x", "caught"),
    record {
      try {
        val a = Res("x")
        throw new RuntimeException(a.name)
      } catch { case _: RuntimeException => OwnershipLog("caught") }
    }
  )

  @Test def dropsOnEarlyReturn(): Unit = {
    assertEquals(List("drop node 1"), record(assertEquals(42, earlyReturn(true))))
    assertEquals(
      List("drop node 2", "drop node 1"),
      record(assertEquals(3, earlyReturn(false)))
    )
  }

  @Test def dropsInPatternMatches(): Unit = {
    assertEquals(List("drop node 3"), record(assertEquals("int 1 3", matching(1))))
    assertEquals(
      List("drop node 3"),
      record(assertEquals("string x", matching("x")))
    )
  }

  @Test def dropsInEveryLoopIteration(): Unit = assertEquals(
    List("iter 0", "drop node 0", "iter 1", "drop node 1"),
    record {
      var i = 0
      while (i < 2) {
        val n = Node(i, Array(i))
        OwnershipLog(s"iter ${n.id}")
        i += 1
      }
    }
  )

  @Test def borrowsInLambdas(): Unit = assertEquals(
    List("sum 6", "inner 1", "drop node 1", "inner 2", "drop node 2", "drop node 0"),
    record {
      val a = Node(0, Array(1, 2, 3))
      val f = () => a.sum
      OwnershipLog(s"sum ${f()}")
      List(1, 2).foreach { i =>
        val inner = Node(i, Array(i))
        OwnershipLog(s"inner ${inner.id}")
      }
    }
  )

  @Test def ownedObjectsKeepGCObjectsAlive(): Unit = assertEquals(
    List("payload 1000", "drop holder 40", "drop node 40"),
    record {
      val holder = Holder(Node(40, Array.fill(1000)(1)))
      for (_ <- 0 until 50) {
        val garbage = new Array[Int](100000)
        garbage(0) = 1
      }
      System.gc()
      OwnershipLog(s"payload ${holder.node.sum}")
    }
  )

  @Test def everythingIsFreed(): Unit = {
    val live = Owned.liveCount()
    val freed = Owned.totalFreed()
    record {
      for (i <- 0 until 1000) {
        val n = Node(i, Array(i))
        Owned.drop(n)
      }
    }
    assertEquals(live, Owned.liveCount())
    assertEquals(freed + 1000, Owned.totalFreed())
  }
}
