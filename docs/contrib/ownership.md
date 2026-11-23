# Ownership for `Owned` objects (experimental)

This is an experiment in giving Scala Native Rust-like ownership semantics,
built on the capture checking and separation checking of Scala 3.9+.

Classes extending `scala.scalanative.memory.Owned` are not allocated on the
garbage collected heap. The compiler tracks who owns each instance and frees
it deterministically when its owner goes out of scope, like Rust drops values.
Separation checking already establishes most of what Rust's borrow checker
establishes: a value passed to a `consume` parameter cannot be used again,
and a fresh value can only hide (capture) consumed values. The new compiler
phase, `scalanative-ownership`, turns that information into `free` calls.

## Example

```scala
import language.experimental.captureChecking
import language.experimental.separationChecking
import scala.scalanative.memory.Owned

class File(val fd: Int) extends Owned:
  override def drop(): Unit = close(fd)          // Rust: impl Drop for File

class Buffered(consume val file: File^) extends Owned  // owns `file`

def process(consume f: File^): Unit = ...       // Rust: fn process(f: File)
def open(path: String): File^ = File(...)       // returns ownership

def main(): Unit =
  val a = open("a.txt")
  val b = open("b.txt")
  process(a)        // `a` is moved; `a.fd` here is a separation failure
  println(b.fd)     // borrow
  val buf = Buffered(open("c.txt"))
// drops: buf (then its field `file`), then b
```

## Semantics

### Owners

An owned value always has exactly one owner:

| Owner                                         | Rust equivalent           |
| --------------------------------------------- | ------------------------- |
| local `val`/`var` initialized with an owned value | `let x = ...`         |
| `consume` parameter                           | by-value parameter        |
| `this` in a `consume` method of an `Owned` class | `fn m(self)`           |
| owning field of an `Owned` class              | struct field              |
| temporary (an owned value not bound to a name)| temporary                 |

A value is owned when it comes from `new C(...)` with `C <: Owned`, or from a
call of a method whose declared result type is an `Owned` type without an
explicit capture set naming other capabilities (`A`, `A^`, but not
`A^{this}` or `A^{x}`, which are borrows). Separation checking guarantees
that such a fresh result only hides consumed arguments.

Owning fields are the fields of `Owned` classes that have an `Owned` type
without explicit captures and are either `consume` class parameters or body
fields. Non-`consume` class parameters are borrowed fields.

### Moves

Ownership moves when an owner is used in one of these positions:

- as an argument of a `consume` parameter, or as the receiver of a `consume`
  method,
- as the result of a method whose result type is owned (including `return`),
- as the right-hand side of an assignment to an owning `var` or owning field,
- as the initializer of a `val` with an explicit type (`val y: A = x`).

`val y = x` with an inferred type merely renames `x`: uses of `y` are uses of
`x`. A value can be moved out of an owning `var` field, which leaves `null`
behind (like `Option::take`); moving out of an owning `val` field is an error.
Moving anything that is not owned (a borrowed field, a non-`consume`
parameter, the result of a method returning a borrow, the result of a generic
method) is an error.

The separation checker rejects uses after a move and moves inside loops.

### Borrows

Every other use is a borrow. A borrow must not outlive its owner (Rust's
"does not live long enough"). The phase computes, for every expression, the
owners its value may retain: receivers and non-`consume` arguments of calls
whose result can hold a reference, owners captured by closures, local methods
and local classes, and the borrows recorded for local values. It reports an
error when such a value

- is returned from the method or from an enclosing method,
- is passed to a `consume` parameter or stored in a field,
- is assigned to a local `var` declared before the owner, or outside of its
  scope.

A value bound to a local declared after the owner, in the same or a nested
scope, is fine: it is dropped first.

### Drops

When a scope ends, its owners are dropped in reverse declaration order, on
every exit: normal completion, `return`, labeled returns of lowered pattern
matches, and exceptions. Dropping runs, in order:

1. the `drop()` hook of the object (Rust's `Drop::drop`),
2. the drop glue: owning fields in declaration order, then those of the
   superclass,
3. `free` of the object's memory.

An owner that is moved on some path gets a drop flag, which is cleared at the
move. Assigning to an owning `var` (local or field) drops its previous value.
`Owned.drop(x)` drops `x` early; it is an ordinary method with a `consume`
parameter and an empty body, exactly like `std::mem::drop`. Calling the
`drop()` hook directly is an error.

## Implementation

- `nativelib/.../memory/Owned.scala`: the `Owned` trait (extends
  `caps.Mutable`) with the `drop()` hook and the drop glue entry point, and
  `Owned.drop`, `Owned.liveCount`, etc.
- `nativelib/.../runtime/OwnedAllocator.scala` and
  `nativelib/src/main/resources/scala-native/memory/Owned.c`: the allocator.
  Owned objects are allocated with `calloc` and kept in a doubly linked list
  that a GC root provider scans during collections, so owned objects can refer
  to garbage collected objects. Allocation and `free` are O(1).
- `nscplugin/.../OwnershipPhase.scala`: the compiler phase. It runs right after
  `cc` and before `elimOpaque`, so it sees pattern matches already lowered to
  labeled blocks and the typer's types with their declared capture sets.
  1. `new C(...)` with `C <: Owned` becomes
     `SafeZoneAllocator.allocate(OwnedAllocator, new C(...))`, reusing the
     lowering of zone allocations (`OwnedAllocator` is a `SafeZone`).
  2. Every `Owned` class with owning fields gets a
     `scalanative$dropOwnedFields` override. The methods are entered for all
     classes of the run before any unit is transformed, so that a subclass can
     call the glue of its superclass with a `super` call.
  3. For each method, an analysis classifies each expression by position (own,
     escape, borrow, discard), finds owners and temporaries, marks moves,
     records the borrows retained by locals (iterated to a fixed point for
     `var`s assigned in loops) and reports errors. A rewrite then inserts drop
     flags and `try`/`finally` drops.

For

```scala
def f(flag: Boolean): Unit =
  val a = Res("a")
  if flag then take(a)
  log("end")
```

the phase produces

```scala
def f(flag: Boolean): Unit =
  val a = SafeZoneAllocator.allocate[Res](OwnedAllocator, new Res("a"))
  var a$owned = true
  try
    if flag then take({ a$owned = false; a })
    log("end")
  finally if a$owned then Owned.scalanative$destroy(a)
```

## Differences from Rust and known gaps

This is an experiment; the following are known limitations.

- Soundness relies on capture and separation checking. Capture sets of type
  arguments are boxed and not tracked by the separation checker, so storing a
  borrowed owned value in a mutable generic collection through a
  non-`consume` parameter is not detected and can lead to a use after free.
- There are no owning generic containers: `List(x)` borrows `x`, and the
  result of a generic method cannot be moved. Fresh owned values whose
  ownership is not taken (for example the results of `xs.map(i => Res(...))`)
  are never dropped; the compiler warns about them.
- Temporaries are dropped at the end of the enclosing block, not at the end
  of the statement.
- Owning fields in traits are not supported. Drop glue methods are added after
  pickling, so a subclass compiled separately from its superclass does not
  call the superclass glue and the superclass's owning fields leak.
- A `consume` constructor parameter cannot be forwarded to a superclass
  constructor (rejected by the separation checker).
- When a later argument of a call throws after an owned argument was moved,
  the moved value leaks.
- Owned objects can only be created in code compiled with
  `separationChecking`, and only with Scala 3.9+. Allocating them in a
  `SafeZone` is an error.
- The garbage collector scans every live owned object as a root during each
  collection.

## Tests

- `nscplugin/src/test/scala-3.9+/.../memory/OwnershipTest.scala`: compile-time
  checks (moves, escapes, explicit destructor calls, field moves).
- `unit-tests/native/src/test/scala-3.9+/.../memory/OwnershipTest.scala`:
  drop order, moves, drop flags, temporaries, drop glue, exceptions, early
  returns, pattern matches, loops, lambdas and GC interaction, at run time.
