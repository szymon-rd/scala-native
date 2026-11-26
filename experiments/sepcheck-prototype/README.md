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
| t7 `boundary`/`break` leaking a scoped capability | compiles | **compiles** |
| t8 capability passed to code without capture checking (Scala, Java `Thread`) | compiles | rejected |
| t9 method compiled without separation checking returning its argument as fresh | compiles | rejected |

All 15 positive programs still compile. On the Scala 3 capture checking tests
(322 pos, 400 neg) the patches change 7 results, all expected consequences:

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
   - `boundary`/`break`: **not fixed.** The inlined `boundary` creates
     `val local: Label[() ->{a.rd} String]`, mentioning a lambda parameter `a`
     that is not in scope, and the value comes back through an `@unchecked`
     `Break` pattern. Making parameters invisible in sibling scopes and not
     widening them to `any` on a level failure fixes it, but also rejects
     legitimate code (`filter-iterable`, `colltest5`); the attempt is in
     `patches/CaptureSet-rejected-attempt.diff`. Not trusting capture sets
     inside type arguments of casts would also close it, at the cost of
     rejecting `boundary` blocks that break with closures over outer
     capabilities.
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

## Not covered

- `boundary`/`break` (above).
- Capture sets nested inside type arguments of casts.
- The Rust-parity changes (moves through hiding, exported move sites for drop
  insertion, drop check, borrow invalidation on `update`) are not prototyped.
