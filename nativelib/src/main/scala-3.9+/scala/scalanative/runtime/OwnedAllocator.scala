package scala.scalanative.runtime

import scala.scalanative.memory.{Owned, SafeZone}
import scala.scalanative.unsafe._

import language.experimental.captureChecking

/** Allocator of `Owned` objects.
 *
 *  The compiler rewrites every instantiation `new C(...)` of a class `C`
 *  extending [[scala.scalanative.memory.Owned]] into
 *  `SafeZoneAllocator.allocate(OwnedAllocator, new C(...))`, which reuses the
 *  lowering of zone allocations: the object is allocated by [[allocImpl]]
 *  outside of the garbage collected heap. Owned objects are reported to the
 *  garbage collector as roots until they are freed, so they can reference
 *  garbage collected objects.
 */
object OwnedAllocator extends SafeZone {
  override def isOpen: Boolean = true

  override private[scalanative] def close(): Unit = ()

  override private[scalanative] def handle: RawPtr =
    Intrinsics.castLongToRawPtr(0L)

  @noinline
  override private[scalanative] def allocImpl(
      cls: RawPtr,
      size: RawSize
  ): RawPtr = Impl.alloc(cls, size)

  def free(obj: Owned^): Unit =
    Impl.free(Intrinsics.castObjectToRawPtr(obj))

  /** Is `obj` a live object allocated by this allocator? Linear in the number
   *  of live owned objects, meant for tests and debugging.
   */
  def isOwned(obj: AnyRef^): Boolean =
    Impl.isOwned(Intrinsics.castObjectToRawPtr(obj))

  def liveCount(): Long = Impl.liveCount().toLong
  def liveBytes(): Long = Impl.liveBytes().toLong
  def totalFreed(): Long = Impl.totalFreed().toLong

  @extern @define("__SCALANATIVE_MEMORY_OWNED") object Impl {
    @name("scalanative_owned_alloc")
    def alloc(rawty: RawPtr, size: RawSize): RawPtr = extern

    @name("scalanative_owned_free")
    def free(obj: RawPtr): Unit = extern

    @name("scalanative_owned_is_owned")
    def isOwned(obj: RawPtr): Boolean = extern

    @name("scalanative_owned_live_count")
    def liveCount(): CSize = extern

    @name("scalanative_owned_live_bytes")
    def liveBytes(): CSize = extern

    @name("scalanative_owned_total_freed")
    def totalFreed(): CSize = extern
  }
}
