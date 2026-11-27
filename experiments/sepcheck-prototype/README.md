# Separation checking fixes for ownership (Scala 3.10.1 prototype)

This prototype patches the Scala 3.10.1 capture and separation checkers so that
ownership-based memory management (see `docs/contrib/ownership.md`) can rely on
them without runtime liveness checks. It targets the holes found while
reviewing the `Owned` experiment: programs that type check and then use freed
memory or free it twice.

The patched files are compiled against the released 3.10.1 compiler and put in
front of it on the classpath; no compiler build is needed.

```
./setup.sh   # fetch compiler sources and the capture checking tests, apply patches
./check.sh   # build, then run the hole suite and the Scala 3 tests, patched vs unpatched
```

`suite/neg` holds one program per hole (each must be rejected), `suite/pos`
programs that must keep compiling. The Scala 3 tests are
`tests/{pos,neg,run}-custom-args/captures` at tag 3.10.1, run with the options
of `CompilationTests` and compared by error lines against the unpatched compiler
(the harness only approximates the official checker, so only differences matter).

## Results

| Hole (`suite/neg`) | Unpatched | Patched |
|---|---|---|
| t1 pure `val` read after consume / escaping a scope | compiles | rejected |
| t2 `h.r` used after `h` was consumed | compiles | rejected |
| t3 owned value widened to `Any` (alias, escaping a scope) | compiles | rejected |
| t4 type pattern upgrading a read-only reference to exclusive | compiles | rejected |
| t5 element taken out of a `List` freed twice | compiles | rejected |
| t6 `consume x: T` with a type parameter | compiles | rejected |
| t7 local `var` freed twice | compiles | rejected |
| t7 SAM-converted lambda leaking its parameter | compiles | rejected |
| t7 `boundary`/`break` leaking a scoped capability | compiles | rejected |
| t8 capability passed to code without capture checking (Scala, Java `Thread`) | compiles | rejected |
| t9 method compiled without separation checking returning its argument as fresh | compiles | rejected |

All 17 positive Tier-1 programs still compile, including `boundary` blocks that break
with closures over capabilities from outside the lambda. On the Scala 3 capture checking tests
(322 pos, 400 neg) the Tier-1 patches change 7 results, all expected
consequences (Tier 2 adds two more, below):

- `paths-simple-suffix-consume` (pos), `paths-complex-consume`,
  `sep-paths-derivedcaps-consume` (neg): `println(x)` of a value capturing a
  stateful object. `println` takes `Any`; it has to become `Any^` (change 3).
- `boxed-consume`, `splits`, `splits-mutable` (neg): these tests encode the
  current rule that `consume` of a boxed argument consumes nothing
  (`f(c) // no consume, it's boxed`), which is the t6 hole.
- `byname-getorelse` (pos): `Option.getOrElse` of the 3.10.1 standard library
  is not capture checked, so its by-name parameter now accepts no capturing
  argument (change 8). A capture checked standard library removes this.

## The changes

Each change is marked `[ownership]` in the patches.

1. **Pure field reads are read uses** (`CheckCaptures.markPathFree`). Selecting
   a field of pure type on a tracked stateful object charges the object as a
   read-only use, like a read-only method call. Before, `free(r); r.name` and
   `() => r.name` did not use `r` at all.
2. **Path prefixes clash** (`SepCheck.ConsumedSet.clashing`). A use of `p.f`
   clashes with an earlier consumption of `p`. (Consuming `p.f` and then using
   `p` was already rejected.)
3. **No widening of stateful captures to `Any`** (`CheckCaptures.checkConformsExpr`).
   A value whose unboxed capture set mentions a stateful object does not
   conform to an expected type `Any`; `Any^` has to be written. Compiler
   generated methods (`equals` of value classes) are exempt. Doing this in the
   type comparer instead (where `T^C <: Any` is special cased) also changes type
   argument bounds and inference and breaks many tests.
4. **Casts keep the captures of their operand** (`CheckCaptures.recheckTypeApply`).
   `asInstanceOf`, which pattern matching uses for type patterns, gets the
   capture set of the value being cast. Capture sets inside type arguments of
   the target type are still trusted.
5. **Moving out of a container consumes it** (`SepCheck.unboxedFrom`). Passing
   `p.m` to a consume parameter, where `m`'s declared result type is a bare type
   parameter (`head: A`, `get: A`), also consumes `p`, like moving out of a
   `Vec` in Rust. Methods with an explicit fresh result (`pop(): T^`) transfer
   ownership as before. 3.10.1 has no reach capabilities, so containers whose
   capture set is untracked are matched by symbol.
6. **Generic consume parameters** (`CheckCaptures.recheckArg`). Arguments of
   `consume x: T` are separation checked even though the formal mentions no
   `any`.
7. **Compiler bugs.**
   - Local variables (`SepCheck`): consuming a `var` also consumes its mirror,
     which is what uses of the variable are charged to; assigning the variable
     forgets that consumption within the current branch.
   - SAM conversion (`CheckCaptures.recheckClosureBlock`): the closure body is
     checked against the SAM method's result type. The original code skipped
     this as a workaround for failures in the standard library's
     `JavaCollectionWrappers.scala`, which this prototype does not compile.
   - Scoped parameters widened to `any` (`CaptureSet.Var.levelOK`,
     `addNewElem`). The inlined `boundary` creates
     `val local: Label[() ->{a.rd} String]`, mentioning a lambda parameter `a`
     that is only in scope inside a sibling lambda. The level check admitted it
     (it only rejects elements nested more deeply than the variable's owner),
     and when an element fails the level check its underlying capture set is
     tried instead, which for `a: Res^` is `any`. Now a parameter is only
     visible inside its own function (or, as before, in the capture set of a
     closure that directly binds it), and a parameter of an anonymous function
     is not widened to a root capability when it fails the level check.
     Variables owned by pattern binders keep the old rule: lambda parameters
     legitimately flow into them when checking arguments (`filter-iterable`).
8. **Code without capture checking accepts no capabilities** (`Setup.fluidify`).
   Parameter types of members of classes that were not capture checked are
   pure instead of fluid, like Rust treating foreign calls as `unsafe`.
9. **Only separation checked signatures transfer ownership**
   (`SepCheck.checkTrustedFreshResult`). A call returning a fresh capability is
   rejected if the method comes from a unit of the same run that was compiled
   without separation checking. Definitions loaded from TASTy are trusted:
   recording whether they were separation checked needs a new TASTy attribute.

## Migration cost

- Standard library signatures that take `Any` and are used with capabilities
  (`println`, string interpolation, `==`) need `Any^`.
- Trusted runtime code compiled without capture checking (allocators, unsafe
  containers) can no longer receive capabilities directly; it needs
  explicit escape hatches (`@untrackedCaptures` fields, `caps.unsafe`) or
  capture checked signatures. Of the explored designs' example programs, the
  two that pass capabilities straight into such code now fail; the one whose
  library stores them in `@untrackedCaptures` fields compiles unchanged.
- The Java and Scala libraries that are not capture checked become unusable
  with capturing arguments (callbacks, by-name parameters) until annotated.

## Rust parity (Tier 2)

These changes make the checker sound for Rust-style implicit drops: every
local owner that is not moved is dropped at the end of its block.

| Hole (`suite/neg`) | Unpatched | Patched |
|---|---|---|
| u14 using a borrow (`val e = b.peek()`) after an update of `b` | compiles | rejected |
| u12 dropping a borrower (`Peek(a)`) at scope end after `a` was consumed | compiles | rejected |
| u10 returning a borrow of a freed value | compiles | rejected |

10. **Hiding is a move** (`SepCheck.checkImplicitDrops`). A local owner that
    the block returns, or that the block's value hides (a returned closure or an
    object capturing it, as in `twoRefs(): Pair^ = { ...; Pair(r1, r2) }`), is
    moved out of the block, as by Rust's `move`; it is not dropped there. This
    is how separation checking already treats hiding in a fresh result type.
    Values without drop glue (closures, objects with borrowed fields) then leak
    rather than being freed while still reachable.
12. **Drop check** (`SepCheck.checkImplicitDrops`). At the end of each block the
    implicit drops of its owners are simulated in reverse declaration order:
    dropping an owner is a use of what it borrows, so dropping `Peek(a)` after
    `a` was consumed is rejected. Owners are locals initialized by a constructor
    or a method declared to return a fresh value; aliases and borrows
    (`val h = x.f`, case class accessors) are not dropped. This is stricter than
    Rust, which has `#[may_dangle]`.
14. **Updates invalidate borrows** (`SepCheck.checkNotInvalidated`). After an
    `update` method call on `b` or an assignment to a field of `b`, a local
    value defined before it whose capture set overlaps `b` can no longer be
    used. Locals are matched by definition order, so no liveness analysis is
    needed; in loops, uses of locals defined before the loop are checked
    against all updates in the loop. Only local values are checked, not
    parameters, and assignments to `@untrackedCaptures` fields are not updates.
    Creating a closure that calls an update method counts as an update, like a
    mutable borrow in Rust: this adds errors to two Scala 3 tests
    (`sepchecks`, `sep-freeze-boxes`) at programs Rust rejects as well.

Item 11 (exporting the move sites so a drop insertion phase needs no analysis
of its own) is not done yet.

## Not covered

- Capture sets nested inside type arguments of casts.
- The pattern-binder exemption in the level check is a heuristic found by the
  tests, not derived from a soundness argument.
- Item 11, and a drop insertion phase driven by it.
