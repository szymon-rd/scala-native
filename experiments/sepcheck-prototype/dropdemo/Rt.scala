// Not capture checked: the runtime of the demo. Freed objects are recorded by
// identity, so a second free or a use after free is detected and logged.
package ownership

trait Droppable { def onDrop(): Unit }

object Rt {
  val log = scala.collection.mutable.ArrayBuffer.empty[String]
  private val freed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]())
  var frees = 0
  def isFreed(x: AnyRef): Boolean = freed.contains(x)
  def free(x: AnyRef): Unit =
    if (!freed.add(x)) log += "DOUBLE FREE" else frees += 1
}

object Drop {
  def destroy(x: Any): Unit = {
    val o = x.asInstanceOf[AnyRef]
    if (Rt.isFreed(o)) Rt.log += "DOUBLE FREE"
    else { o.asInstanceOf[Droppable].onDrop(); Rt.free(o) }
  }
}
