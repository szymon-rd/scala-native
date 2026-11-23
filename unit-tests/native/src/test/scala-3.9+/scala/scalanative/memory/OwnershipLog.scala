package scala.scalanative.memory

/* Records drops for OwnershipTest. Kept out of capture checked code. */
object OwnershipLog {
  private val events = scala.collection.mutable.ArrayBuffer.empty[String]

  def apply(event: String): Unit = events += event

  /** Runs `body` and returns the events it recorded. */
  def record(body: => Unit): List[String] = {
    events.clear()
    body
    val result = events.toList
    events.clear()
    result
  }
}
