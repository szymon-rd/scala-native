package scala.scalanative.nscplugin

import dotty.tools.dotc
import scala.collection.mutable

import dotc.ast.tpd
import dotc.ast.tpd._
import dotc.core.Constants.Constant
import dotc.core.Contexts._
import dotc.core.Decorators._
import dotc.core.DenotTransformers.IdentityDenotTransformer
import dotc.core.Flags._
import dotc.core.NameKinds.UniqueName
import dotc.core.Names._
import dotc.core.StdNames._
import dotc.core.Symbols._
import dotc.core.Types._
import dotc.plugins.PluginPhase
import dotc.report
import dotc.util.SrcPos

/** Rust-like ownership for `scala.scalanative.memory.Owned`.
 *
 *  The phase runs right after capture and separation checking (`cc`). The
 *  separation checker guarantees that a value is not used after it was consumed
 *  (moved). This phase uses the same notion of consumption to decide where
 *  owned values have to be dropped, and inserts the drops:
 *
 *    - `new C(...)` for a class `C` extending `Owned` is allocated by
 *      `scala.scalanative.runtime.OwnedAllocator`, outside of the GC heap.
 *    - Owners are local `val`s and `var`s holding owned values, `consume`
 *      parameters and `this` in `consume` methods of `Owned` classes, owning
 *      fields of `Owned` classes, and temporaries (owned values that are
 *      created but not bound to anything).
 *    - An owner is dropped when its scope ends, on every exit path (normal,
 *      `return`, labeled returns and exceptions), in reverse declaration order.
 *      Owners that were moved on some path carry a drop flag.
 *    - Moves are: `consume` arguments and receivers of `consume` methods,
 *      returning a value from a method with an owned result type, storing a
 *      value in a `var`, an owning field, or a `val` with an explicit type.
 *    - Any other use is a borrow. A borrow must not outlive its owner: a value
 *      that may retain a borrowed owner cannot escape the owner's scope (Rust's
 *      "does not live long enough").
 *    - Every `Owned` class gets drop glue dropping its owning fields.
 */
object OwnershipPhase {
  val name = "scalanative-ownership"

  private val DropFieldsName = "scalanative$dropOwnedFields".toTermName
  private val DestroyName = "scalanative$destroy".toTermName
  private val DropName = "drop".toTermName
}

class OwnershipPhase extends PluginPhase with IdentityDenotTransformer {
  import OwnershipPhase._

  override val phaseName: String = OwnershipPhase.name
  override val runsAfter: Set[String] = Set("cc")
  override val runsBefore: Set[String] = Set("elimOpaque")
  override def description: String =
    "drop owned values when their owner goes out of scope"
  // Drop glue methods are added to classes extending Owned.
  override def changesMembers: Boolean = true

  // ---------------------------------------------------------------------
  // Definitions

  private final class Defs(using Context) {
    val OwnedClass: Symbol =
      getClassIfDefined("scala.scalanative.memory.Owned")
    val exists: Boolean = OwnedClass.exists

    lazy val OwnedModule: Symbol = OwnedClass.companionModule
    lazy val destroy: Symbol = OwnedModule.info.decl(DestroyName).symbol
    lazy val dropHook: Symbol = OwnedClass.info.decl(DropName).symbol
    lazy val dropFields: Symbol = OwnedClass.info.decl(DropFieldsName).symbol
    lazy val allocatorModule: Symbol =
      requiredModule("scala.scalanative.runtime.OwnedAllocator")
    lazy val allocate: Symbol =
      requiredModule("scala.scalanative.runtime.SafeZoneAllocator")
        .requiredMethod("allocate")

    val consumeAnnot: Symbol = getClassIfDefined("scala.caps.internal.consume")
    val retainsAnnots: Set[Symbol] = Set(
      "scala.annotation.retains",
      "scala.annotation.retainsCap",
      "scala.annotation.retainsByName"
    ).map(getClassIfDefined(_)).filter(_.exists)
    val retainsCapAnnot: Symbol =
      getClassIfDefined("scala.annotation.retainsCap")
    val rootCaps: Set[Symbol] =
      Set("scala.caps.any", "scala.caps.fresh", "scala.caps.cap")
        .map(getModuleIfDefined(_))
        .filter(_.exists)
        .flatMap(m => Set(m, m.moduleClass))
  }

  private var defs: Defs = null
  private var defsRunId: Int = -1
  /* Drop glue methods, entered for all classes of the run before any unit
   * is transformed, so that a subclass can call the glue of its parent. */
  private val glueMethods = mutable.HashMap.empty[Symbol, Symbol]

  private def ownedEnabled(using Context): Boolean =
    defs != null && defs.exists

  private def ownershipChecked(using Context): Boolean =
    ownedEnabled && CompilerCompat.sepChecksEnabled

  override def prepareForUnit(tree: Tree)(using Context): Context = {
    if (defsRunId != ctx.runId) {
      defsRunId = ctx.runId
      defs = Defs()
      glueMethods.clear()
      if (defs.exists)
        ctx.run.units.foreach { unit =>
          val unitCtx = ctx.fresh.setCompilationUnit(unit)
          if (CompilerCompat.sepChecksEnabled(using unitCtx))
            enterGlueMethods(unit.tpdTree)(using unitCtx)
        }
    }
    ctx
  }

  // ---------------------------------------------------------------------
  // Type level helpers

  private def isBottom(tp: Type)(using Context): Boolean = {
    val cls = tp.classSymbol
    cls == defn.NothingClass || cls == defn.NullClass
  }

  /** Is `tp` the type of an owned object? */
  private def isOwnedType(tp: Type)(using Context): Boolean = {
    val w = tp.widenDealias
    !w.isInstanceOf[MethodicType] && !isBottom(w) &&
      w.derivesFrom(defs.OwnedClass)
  }

  /** Can a value of type `tp` hold a reference to an owned object? */
  private def mayRetain(tp: Type)(using Context): Boolean = {
    val w = tp.widenDealias
    val cls = w.classSymbol
    !(w.isInstanceOf[ValueType] && pureClasses.contains(cls) || isBottom(w))
  }

  /** Classes whose instances cannot refer to other objects. */
  private def pureClasses(using Context): Set[Symbol] = Set(
    defn.UnitClass,
    defn.BooleanClass,
    defn.CharClass,
    defn.ByteClass,
    defn.ShortClass,
    defn.IntClass,
    defn.LongClass,
    defn.FloatClass,
    defn.DoubleClass,
    defn.StringClass
  )

  /** Does `tp` carry an explicit capture set naming capabilities other than the
   *  root capability, e.g. `A^{x}` or `A^{this}`? Such types are borrows.
   */
  private def hasBorrowCaptures(tp: Type)(using Context): Boolean = tp match {
    case AnnotatedType(parent, annot) =>
      val annotSym = annot.symbol
      val borrows =
        defs.retainsAnnots.contains(annotSym) &&
          annotSym != defs.retainsCapAnnot && {
            annot.tree.tpe match {
              case AppliedType(_, List(elems)) => !onlyRootCaps(elems)
              case _                           => true
            }
          }
      borrows || hasBorrowCaptures(parent)
    case tp: TypeRef if tp.symbol.isAliasType =>
      hasBorrowCaptures(tp.dealias)
    case _ => false
  }

  private def onlyRootCaps(elems: Type)(using Context): Boolean =
    elems.dealias match {
      case OrType(l, r)     => onlyRootCaps(l) && onlyRootCaps(r)
      case tp: TermRef      => defs.rootCaps.contains(tp.symbol)
      case tp: TypeRef      => defs.rootCaps.contains(tp.symbol)
      case tp: ConstantType => false
      case tp if tp.isRef(defn.NothingClass) => true
      case _                                 => false
    }

  /** Does calling `meth` produce a fresh owned value, owned by the caller?
   *  Separation checking guarantees that a fresh result type (`A^` or a
   *  capability class type without captures) only hides consumed arguments.
   */
  private def returnsOwnership(meth: Symbol)(using Context): Boolean =
    meth.is(Method) && !meth.isConstructor && {
      val res = meth.info.finalResultType
      isOwnedType(res) && !hasBorrowCaptures(res)
    }

  private def isConsume(sym: Symbol)(using Context): Boolean =
    defs.consumeAnnot.exists &&
      (sym.hasAnnotation(defs.consumeAnnot) ||
      sym.info.hasAnnotation(defs.consumeAnnot.asClass))

  private def isField(sym: Symbol)(using Context): Boolean =
    sym.exists && sym.isTerm && !sym.is(Method) && !sym.is(Module) &&
      sym.owner.isClass

  /** Is `field` an owning field, dropped by the drop glue of its class? */
  private def isOwningField(field: Symbol)(using Context): Boolean =
    isField(field) && !field.is(Lazy) &&
      field.owner != defs.OwnedClass &&
      field.owner.derivesFrom(defs.OwnedClass) &&
      isOwnedType(field.info) && !hasBorrowCaptures(field.info) &&
      (!field.is(ParamAccessor) || isConsume(field))

  private def owningFieldsOf(cls: Symbol)(using Context): List[Symbol] =
    cls.info.decls.toList.filter(isOwningField)

  /** The class of `new C(...)` if `C` extends `Owned`. */
  private def newOwnedClass(tree: Tree)(using Context): Option[Symbol] =
    if (tree.tpe.widen.isInstanceOf[MethodicType]) None
    else
      methPart(tree) match {
        case Select(nu: New, nme.CONSTRUCTOR) =>
          val cls = nu.tpe.classSymbol
          if (cls.exists && cls.derivesFrom(defs.OwnedClass)) Some(cls)
          else None
        case _ => None
      }

  private def isOwnedAllocation(tree: Tree)(using Context): Boolean =
    tree match {
      case Apply(fn, List(zone, _)) =>
        fn.symbol == defs.allocate &&
          zone.symbol == defs.allocatorModule
      case _ => false
    }

  /** The `new C(...)` call of an owned allocation, otherwise `tree`. */
  private def unwrapAllocation(tree: Tree)(using Context): Tree = tree match {
    case Apply(_, List(_, obj)) if isOwnedAllocation(tree) => obj
    case _                                                 => tree
  }

  /** Does evaluating `tree` produce a fresh owned value? */
  private def isFresh(tree: Tree)(using Context): Boolean = tree match {
    case _ if isOwnedAllocation(tree)                   => true
    case _ if newOwnedClass(tree).nonEmpty              => true
    case _: Apply | _: Select | _: Ident | _: TypeApply =>
      !tree.tpe.widen.isInstanceOf[MethodicType] &&
        tree.symbol.exists && returnsOwnership(tree.symbol)
    case _ => false
  }

  private def isNullLiteral(tree: Tree): Boolean = tree match {
    case Literal(c) => c.tag == dotc.core.Constants.NullTag
    case _          => false
  }

  private def isInferredTypeTree(tpt: Tree): Boolean =
    tpt.isInstanceOf[InferredTypeTree]

  private def destroyCall(value: Tree)(using Context): Tree =
    ref(defs.destroy).appliedTo(value)

  // ---------------------------------------------------------------------
  // Allocation

  override def transformApply(tree: Apply)(using Context): Tree =
    if (!ownedEnabled) tree
    else if (tree.fun.symbol == defs.allocate) {
      tree.args match {
        case List(_, obj) if isOwnedAllocation(obj) =>
          report.error(
            "Instances of classes extending Owned cannot be allocated in a SafeZone",
            tree.srcPos
          )
        case _ => ()
      }
      tree
    } else
      newOwnedClass(tree) match {
        case Some(cls) if !cls.is(Trait) =>
          if (!CompilerCompat.sepChecksEnabled) {
            report.error(
              s"${cls.show} extends Owned: instances of Owned classes can only be created " +
                "in code compiled with `import language.experimental.separationChecking`",
              tree.srcPos
            )
            tree
          } else {
            val allocated = Apply(
              ref(defs.allocate).appliedToType(tree.tpe.widen),
              List(ref(defs.allocatorModule), tree)
            )
            allocated.withType(tree.tpe)
          }
        case _ => tree
      }

  // ---------------------------------------------------------------------
  // Drop glue

  private def enterGlueMethods(tree: Tree)(using Context): Unit =
    new TreeTraverser {
      def traverse(tree: Tree)(using Context): Unit = tree match {
        case cdef: TypeDef if cdef.isClassDef =>
          val cls = cdef.symbol
          if (cls != defs.OwnedClass && cls.derivesFrom(defs.OwnedClass) &&
              !cls.is(Trait) && owningFieldsOf(cls).nonEmpty) {
            val glue = newSymbol(
              cls,
              DropFieldsName,
              Method | Override | Synthetic,
              MethodType(Nil, defn.UnitType),
              coord = cdef.span
            ).asTerm
            glue.enteredAfter(OwnershipPhase.this)
            glueMethods(cls) = glue
          }
          traverseChildren(tree)
        case _ => traverseChildren(tree)
      }
    }.traverse(tree)

  override def transformTemplate(tree: Template)(using Context): Tree =
    if (!ownedEnabled) tree
    else {
      val cls = ctx.owner
      // Constructor calls of parents are not allocations.
      val parents = tree.parents.map {
        case app @ Apply(_, List(_, obj)) if isOwnedAllocation(app) => obj
        case parent                                                 => parent
      }
      val glue =
        if (!CompilerCompat.sepChecksEnabled || !cls.isClass) Nil
        else checkOwnedClass(cls, tree)
      cpy.Template(tree)(parents = parents, body = tree.body ++ glue)
    }

  private def checkOwnedClass(cls: Symbol, tree: Template)(using
      Context
  ): List[Tree] = {
    val isOwnedClass =
      cls != defs.OwnedClass && cls.derivesFrom(defs.OwnedClass)
    tree.body.foreach {
      case vd: ValDef if isField(vd.symbol) && isOwnedType(vd.symbol.info) =>
        val field = vd.symbol
        if (isOwningField(field)) {
          if (cls.is(Trait))
            report.error(
              s"Owning field ${field.name} in a trait is not supported, declare it in a class",
              vd.srcPos
            )
          if (!vd.rhs.isEmpty)
            checkFieldInitializer(vd.rhs, field)
        } else if (!isOwnedClass && !field.is(ParamAccessor) &&
            !vd.rhs.isEmpty && leaves(vd.rhs).exists(isFresh))
          report.warning(
            s"Owned value stored in field ${field.name} of ${cls.show}, which does not extend Owned, is never dropped",
            vd.rhs.srcPos
          )
      case _ => ()
    }
    glueMethods.get(cls).toList.map { glue =>
      val fields = owningFieldsOf(cls)
      val drops = fields.map(f => destroyCall(This(cls.asClass).select(f)))
      val superGlue = cls.info.baseClasses.tail.iterator
        .filter(_ != defs.OwnedClass)
        .map(bc => atPhase(this.next)(bc.info.decl(DropFieldsName).symbol))
        .find(_.exists)
      val superCall = superGlue.map { target =>
        Super(This(cls.asClass), tpnme.EMPTY).select(target).appliedToNone
      }
      DefDef(glue.asTerm, _ => Block(drops ++ superCall, unitLiteral))
    }
  }

  /** Owning fields take ownership of their initial value. */
  private def checkFieldInitializer(rhs: Tree, field: Symbol)(using
      Context
  ): Unit =
    leaves(rhs).foreach { leaf =>
      if (!isFresh(leaf) && !isNullLiteral(leaf) && !isBottom(leaf.tpe) &&
          isOwnedType(leaf.tpe) && !isConsumeParamRef(leaf))
        report.error(
          s"Owning field ${field.name} has to be initialized with a fresh owned value or a consume parameter, " +
            "but this value is borrowed",
          leaf.srcPos
        )
    }

  private def isConsumeParamRef(tree: Tree)(using Context): Boolean =
    tree match {
      case Ident(_) | Select(This(_), _) =>
        tree.symbol.is(ParamAccessor) && isConsume(tree.symbol)
      case _ => false
    }

  /** The expressions whose values a tree may evaluate to. */
  private def leaves(tree: Tree)(using Context): List[Tree] = tree match {
    case Typed(expr, _)      => leaves(expr)
    case Inlined(_, _, expr) => leaves(expr)
    case Block(_, expr)      => leaves(expr)
    case If(_, thenp, elsep) => leaves(thenp) ++ leaves(elsep)
    case Try(expr, cases, _) =>
      leaves(expr) ++ cases.flatMap(c => leaves(c.body))
    case Match(_, cases)     => cases.flatMap(c => leaves(c.body))
    case Labeled(bind, expr) =>
      val returns = mutable.ListBuffer.empty[Tree]
      new TreeTraverser {
        def traverse(t: Tree)(using Context): Unit = t match {
          case Return(e, from) if from.symbol == bind.symbol =>
            returns ++= leaves(e)
          case _: DefDef => ()
          case _         => traverseChildren(t)
        }
      }.traverse(expr)
      leaves(expr) ++ returns
    case _ => List(tree)
  }

  // ---------------------------------------------------------------------
  // Methods

  override def transformDefDef(tree: DefDef)(using Context): Tree = {
    val meth = tree.symbol
    if (!ownershipChecked || tree.rhs.isEmpty || meth.isConstructor ||
        meth.is(Inline) || meth.is(Deferred))
      tree
    else {
      val analysis = new MethodOwnership(tree)
      if (!analysis.hasWork) tree
      else
        cpy.DefDef(tree)(rhs = analysis.rewrite()(using ctx.withOwner(meth)))
    }
  }

  private enum Pos {

    /** Ownership of the value is transferred to the context. */
    case Own

    /** The value leaves the scope, but its ownership is not transferred. */
    case Escape

    /** The value is used temporarily. */
    case Borrow

    /** The value is discarded. */
    case Discard
  }

  private final class Scope(val parent: Scope, val owner: Symbol) {
    val temps = mutable.LinkedHashSet.empty[Owner]
    def isWithin(other: Scope): Boolean =
      (this eq other) || (parent != null && parent.isWithin(other))
  }

  private enum OwnerKind { case Local, Var, Param, This, Temp }

  private final class Owner(
      val sym: Symbol,
      val kind: OwnerKind,
      val scope: Scope,
      val order: Int,
      val tpe: Type
  ) {
    var needsFlag: Boolean =
      kind == OwnerKind.Var || kind == OwnerKind.Temp
    var initiallyOwned: Boolean = kind != OwnerKind.Temp
    var flag: Symbol = NoSymbol

    def describe(using Context): String = kind match {
      case OwnerKind.This => "`this`"
      case OwnerKind.Temp => "a temporary owned value"
      case _              => s"`${sym.name.show}`"
    }
  }

  /** Ownership analysis and rewriting of one method body. */
  private final class MethodOwnership(dd: DefDef)(using Context) {
    private val meth = dd.symbol
    private val cls = meth.owner

    private val root = new Scope(null, meth)
    private val scopes = mutable.HashMap.empty[Tree, Scope]
    private val owners = mutable.LinkedHashMap.empty[Symbol, Owner]
    private val aliases = mutable.HashMap.empty[Symbol, Owner]
    private val locals = mutable.HashMap.empty[Symbol, (Scope, Int)]
    private val retains = mutable.HashMap.empty[Symbol, Set[Owner]]
    private val temps = mutable.HashMap.empty[Tree, Owner]
    private val labelPos = mutable.HashMap.empty[Symbol, Pos]
    private val localDefs = mutable.HashMap.empty[Symbol, Tree]

    // Decisions consumed by `rewrite`.
    private val moves = mutable.HashSet.empty[Tree]
    private val discards = mutable.HashSet.empty[Tree]
    private val fieldTakes = mutable.HashSet.empty[Tree]
    private val ownerAssigns = mutable.HashSet.empty[Tree]
    private val fieldAssigns = mutable.HashSet.empty[Tree]

    private var order = 0
    private var reporting = false
    private val reported = mutable.HashSet.empty[(Int, String)]

    private val thisOwner: Option[Owner] =
      if (cls.isClass && cls.derivesFrom(defs.OwnedClass) &&
          meth.hasAnnotation(defs.consumeAnnot) && !meth.isStatic)
        Some(newOwner(cls, OwnerKind.This, root, cls.thisType))
      else None

    private val paramOwners: List[Owner] =
      if (meth == defs.destroy) Nil
      else
        dd.termParamss.flatten.collect {
          case vd if isConsume(vd.symbol) && isOwnedType(vd.symbol.info) =>
            newOwner(vd.symbol, OwnerKind.Param, root, vd.symbol.info)
        }

    private val bodyPos: Pos =
      if (returnsOwnership(meth)) Pos.Own else Pos.Escape

    private def newOwner(
        sym: Symbol,
        kind: OwnerKind,
        scope: Scope,
        tpe: Type
    ): Owner =
      owners.getOrElseUpdate(
        sym, {
          order += 1
          new Owner(sym, kind, scope, order, tpe)
        }
      )

    def hasWork: Boolean = {
      collectLocalDefs()
      // Iterate until the borrows retained by locals are stable, then run
      // once more to report errors.
      var size = -1
      var iterations = 0
      while (size != retainsSize && iterations < 10) {
        size = retainsSize
        iterations += 1
        analyze()
      }
      reporting = true
      analyze()
      owners.nonEmpty || temps.nonEmpty || discards.nonEmpty ||
        fieldTakes.nonEmpty || fieldAssigns.nonEmpty
    }

    private def retainsSize: Int = retains.valuesIterator.map(_.size).sum

    private def error(msg: String, pos: SrcPos): Unit =
      if (reporting && reported.add((pos.span.start, msg)))
        report.error(msg, pos)

    private def warning(msg: String, pos: SrcPos): Unit =
      if (reporting && reported.add((pos.span.start, msg)))
        report.warning(msg, pos)

    private def collectLocalDefs(): Unit =
      new TreeTraverser {
        def traverse(tree: Tree)(using Context): Unit = {
          tree match {
            case dd: DefDef if dd.symbol != meth => localDefs(dd.symbol) = dd
            case td: TypeDef if td.isClassDef    => localDefs(td.symbol) = td
            case _                               => ()
          }
          traverseChildren(tree)
        }
      }.traverse(dd.rhs)

    // --------------------------------------------------------------------
    // Pass 1: analysis

    private def analyze(): Unit = {
      order = owners.size + temps.size
      labelPos.clear()
      scopeOwner = meth
      visit(dd.rhs, bodyPos)(using root)
      checkTarget(dd.rhs, bodyPos, None, s"is returned from ${meth.name.show}")
    }

    private def ownerOf(tree: Tree): Option[Owner] = tree match {
      case id: Ident =>
        owners.get(id.symbol).orElse(aliases.get(id.symbol))
      case th: This =>
        thisOwner.filter(_ => th.symbol == cls)
      case _ => None
    }

    private def fieldOf(tree: Tree): Option[Symbol] = tree match {
      case Select(_, _) | Ident(_) if isField(tree.symbol) => Some(tree.symbol)
      case _                                               => None
    }

    /** Owners whose ownership a value in an `Own` position is moved from. */
    private def directOwners(tree: Tree): Set[Owner] =
      leaves(tree).flatMap(ownerOf).toSet

    /** Owners that the value of `tree` may refer to. */
    private def retained(tree: Tree): Set[Owner] =
      if (!mayRetain(tree.tpe)) Set.empty
      else
        temps.get(tree) match {
          case Some(temp) => retains.getOrElse(temp.sym, Set.empty) + temp
          case None       => retainedUncached(tree)
        }

    private def retainedUncached(tree: Tree): Set[Owner] = tree match {
      case _: Ident | _: This if ownerOf(tree).nonEmpty =>
        val owner = ownerOf(tree).get
        retains.getOrElse(owner.sym, Set.empty) + owner
      case id: Ident if id.symbol.exists && localDefs.contains(id.symbol) =>
        freeOwners(id.symbol)
      case id: Ident          => retains.getOrElse(id.symbol, Set.empty)
      case _ if isFresh(tree) =>
        if (isOwnedAllocation(tree) || newOwnedClass(tree).nonEmpty)
          retainedByCall(tree)
        else Set.empty
      case Typed(expr, _)      => retained(expr)
      case Inlined(_, _, expr) => retained(expr)
      case Block(_, expr)      => retained(expr)
      case If(_, thenp, elsep) => retained(thenp) ++ retained(elsep)
      case Try(expr, cases, _) =>
        retained(expr) ++ cases.flatMap(c => retained(c.body))
      case Match(_, cases)     => cases.flatMap(c => retained(c.body)).toSet
      case Labeled(_, _)       => leaves(tree).flatMap(retained).toSet
      case Closure(env, fn, _) =>
        env.flatMap(retained).toSet ++ freeOwners(fn.symbol)
      case SeqLiteral(elems, _)                => elems.flatMap(retained).toSet
      case _: Apply | _: Select | _: TypeApply => retainedByCall(tree)
      case _: Literal | _: Assign | _: WhileDo | _: Return | _: New | _: This =>
        Set.empty
      case _ =>
        var acc = Set.empty[Owner]
        tree.foreachSubTree { sub =>
          if (sub ne tree) ownerOf(sub).foreach(o => acc += o)
        }
        acc
    }

    /** A call may retain its non-consumed receiver and arguments. */
    private def retainedByCall(tree: Tree): Set[Owner] = {
      val (core, argss) = splitApply(unwrapAllocation(tree))
      val fn = core.symbol
      val consumes = consumeFlags(fn, argss)
      val fromArgs = argss.zip(consumes).flatMap { (args, flags) =>
        args.zip(flags).collect { case (arg, false) => retained(arg) }.flatten
      }
      val fromReceiver = receiverOf(core) match {
        case Some(qual) if !fn.hasAnnotation(defs.consumeAnnot) =>
          retained(qual)
        case _ => Set.empty
      }
      val fromLocalDef =
        if (fn.exists && localDefs.contains(fn)) freeOwners(fn)
        else Set.empty
      fromArgs.toSet ++ fromReceiver ++ fromLocalDef
    }

    private val freeOwnersCache = mutable.HashMap.empty[Symbol, Set[Owner]]

    /** Owners referenced from the body of a local method or class. */
    private def freeOwners(sym: Symbol): Set[Owner] =
      freeOwnersCache.get(sym) match {
        case Some(owned) => owned
        case None        =>
          freeOwnersCache(sym) = Set.empty // cycles
          val result = localDefs.get(sym) match {
            case None       => Set.empty[Owner]
            case Some(tree) =>
              var acc = Set.empty[Owner]
              tree.foreachSubTree {
                case sub @ (_: Ident | _: This) =>
                  ownerOf(sub) match {
                    case Some(o) =>
                      acc = acc ++ retains.getOrElse(o.sym, Set.empty) + o
                    case None =>
                      val s = sub.symbol
                      if (s.exists && s != sym && localDefs.contains(s))
                        acc = acc ++ freeOwners(s)
                      else acc = acc ++ retains.getOrElse(s, Set.empty)
                  }
                case nu: New if localDefs.contains(nu.tpe.classSymbol) =>
                  acc = acc ++ freeOwners(nu.tpe.classSymbol)
                case _ => ()
              }
              acc
          }
          freeOwnersCache(sym) = result
          result
      }

    /** Check that a value in an `Own` or `Escape` position does not retain a
     *  borrowed owner that may be dropped before the value.
     *
     *  @param target
     *    the owner that takes the value, if any
     */
    private def checkTarget(
        tree: Tree,
        pos: Pos,
        target: Option[Owner],
        what: String
    ): Unit = {
      val direct = if (pos == Pos.Own) directOwners(tree) else Set.empty
      val borrowed = retained(tree) -- direct
      borrowed.foreach { o =>
        val outlived = target.exists { t =>
          t.scope.isWithin(o.scope) && t.order > o.order
        }
        if (!outlived) {
          val how =
            if (directOwners(tree).contains(o))
              s"${o.describe} does not live long enough: it $what but it is dropped at the end of its scope"
            else
              s"${o.describe} does not live long enough: it is borrowed by a value that $what"
          error(how, tree.srcPos)
        }
      }
      target.foreach { t =>
        val newRetains = retains.getOrElse(t.sym, Set.empty) ++ borrowed
        retains(t.sym) = newRetains
      }
    }

    private def visitOwn(tree: Tree, target: Option[Owner], what: String)(using
        Scope
    ): Unit = {
      visit(tree, Pos.Own)
      checkTarget(tree, Pos.Own, target, what)
    }

    private def visitEscape(tree: Tree, what: String)(using Scope): Unit = {
      visit(tree, Pos.Escape)
      checkTarget(tree, Pos.Escape, None, what)
    }

    private def visitChildren(tree: Tree)(using Scope): Unit =
      new TreeTraverser {
        private var top = true
        def traverse(t: Tree)(using Context): Unit =
          if (top) { top = false; traverseChildren(t) }
          else visit(t, Pos.Borrow)
      }.traverse(tree)

    private def visit(tree: Tree, pos: Pos)(using scope: Scope): Unit =
      tree match {
        case EmptyTree                                    => ()
        case _: Ident | _: This if ownerOf(tree).nonEmpty =>
          val owner = ownerOf(tree).get
          if (pos == Pos.Own) {
            owner.needsFlag = true
            moves += tree
          }

        case _: DefDef | _: TypeDef | _: Import | _: Export => ()

        case vd: ValDef => visitValDef(vd)

        case Block(stats, expr) =>
          val inner = scopes.getOrElseUpdate(tree, new Scope(scope, scopeOwner))
          stats.foreach(visitStat(_)(using inner))
          visit(expr, pos)(using inner)

        case Inlined(_, bindings, expansion) =>
          val inner = scopes.getOrElseUpdate(tree, new Scope(scope, scopeOwner))
          bindings.foreach(visitStat(_)(using inner))
          visit(expansion, pos)(using inner)

        case Typed(expr, _) => visit(expr, pos)

        case If(cond, thenp, elsep) =>
          visit(cond, Pos.Borrow)
          visit(thenp, pos)
          visit(elsep, pos)

        case Labeled(bind, expr) =>
          labelPos(bind.symbol) = pos
          visit(expr, pos)

        case Return(expr, from) =>
          labelPos.get(from.symbol) match {
            case Some(lpos) => visit(expr, lpos)
            case None       =>
              if (from.symbol == meth) {
                visit(expr, bodyPos)
                checkTarget(
                  expr,
                  bodyPos,
                  None,
                  s"is returned from ${meth.name.show}"
                )
              } else visitEscape(expr, "is returned from an enclosing method")
          }

        case Try(expr, cases, finalizer) =>
          visit(expr, pos)
          cases.foreach { c =>
            visit(c.guard, Pos.Borrow)
            visit(c.body, pos)
          }
          visit(finalizer, Pos.Discard)

        case Match(selector, cases) =>
          visit(selector, Pos.Borrow)
          cases.foreach { c =>
            visit(c.guard, Pos.Borrow)
            visit(c.body, pos)
          }

        case WhileDo(cond, body) =>
          visit(cond, Pos.Borrow)
          visit(body, Pos.Discard)

        case asg: Assign => visitAssign(asg)

        case Closure(env, _, _) => env.foreach(visit(_, Pos.Borrow))

        case _: Apply | _: TypeApply | _: Select | _: Ident
            if tree.symbol.is(Method) &&
              !tree.tpe.widen.isInstanceOf[MethodicType] =>
          visitCall(tree)
          visitValue(tree, pos)

        case Select(qual, _) =>
          visit(qual, Pos.Borrow)
          visitValue(tree, pos)

        case _: Ident | _: This | _: Literal =>
          visitValue(tree, pos)

        case _ =>
          visitChildren(tree)
          visitValue(tree, pos)
      }

    /** The owner of definitions at the current point of the traversal. */
    private var scopeOwner: Symbol = meth

    private def visitStat(stat: Tree)(using Scope): Unit = stat match {
      case vd: ValDef => visitValDef(vd)
      case _          => visit(stat, Pos.Discard)
    }

    private def visitCall(tree: Tree)(using Scope): Unit = {
      val (core, argss) = splitApply(unwrapAllocation(tree))
      val fn = core.symbol
      if (isExplicitDropCall(fn, core))
        error(
          "The destructor drop() cannot be called explicitly, use scala.scalanative.memory.Owned.drop(value) to drop a value early",
          tree.srcPos
        )
      receiverOf(core).foreach { qual =>
        if (fn.hasAnnotation(defs.consumeAnnot))
          visitOwn(
            qual,
            None,
            s"is the receiver of consume method ${fn.name.show}"
          )
        else visit(qual, Pos.Borrow)
      }
      core match {
        case TypeApply(inner, _) if receiverOf(inner).isEmpty =>
          visit(inner, Pos.Borrow)
        case _ => ()
      }
      val consumes = consumeFlags(fn, argss)
      argss.zip(consumes).foreach { (args, flags) =>
        args.zip(flags).foreach { (arg, consume) =>
          if (consume)
            visitOwn(
              arg,
              None,
              s"is passed to a consume parameter of ${fn.name.show}"
            )
          else visit(arg, Pos.Borrow)
        }
      }
    }

    private def isExplicitDropCall(fn: Symbol, core: Tree): Boolean =
      fn.exists && fn.name == DropName && fn.info.paramInfoss.flatten.isEmpty &&
        (fn == defs.dropHook || fn.allOverriddenSymbols.contains(
          defs.dropHook
        )) &&
        !(receiverOf(core).exists(_.isInstanceOf[Super])) &&
        !ctx.owner.ownersIterator.contains(defs.destroy) &&
        meth != defs.destroy

    /** Handle the value of a non-owner expression in position `pos`. */
    private def visitValue(tree: Tree, pos: Pos)(using scope: Scope): Unit =
      pos match {
        case Pos.Own =>
          if (isFresh(tree) || isNullLiteral(tree) || isBottom(tree.tpe) ||
              !isOwnedType(tree.tpe)) ()
          else
            fieldOf(tree) match {
              case Some(field) if isOwningField(field) && field.is(Mutable) =>
                fieldTakes += tree
              case Some(field) if isOwningField(field) =>
                error(
                  s"Cannot move out of owning field ${field.name.show}, it is dropped together with its object. " +
                    "Declare it as a var to move a value out of it.",
                  tree.srcPos
                )
              case _ =>
                error(
                  "Cannot move a borrowed value. Ownership can only be moved out of an owner: " +
                    "a local value, a consume parameter, the receiver of a consume method or an owning var field.",
                  tree.srcPos
                )
            }
        case Pos.Borrow =>
          if (isFresh(tree)) {
            val temp = temps.getOrElseUpdate(
              tree, {
                order += 1
                val sym = newSymbol(
                  scope.owner,
                  UniqueName.fresh("owned$tmp".toTermName),
                  Mutable | Synthetic,
                  tree.tpe.widen
                )
                new Owner(sym, OwnerKind.Temp, scope, order, tree.tpe.widen)
              }
            )
            scope.temps += temp
            val borrowed = retainedUncached(tree)
            retains(temp.sym) =
              retains.getOrElse(temp.sym, Set.empty) ++ borrowed
          }
        case Pos.Discard =>
          if (isFresh(tree)) discards += tree
        case Pos.Escape =>
          if (isFresh(tree))
            warning(
              "This owned value is never dropped: its ownership is not taken by an owner",
              tree.srcPos
            )
      }

    private def visitValDef(vd: ValDef)(using scope: Scope): Unit = {
      val sym = vd.symbol
      val rhs = vd.rhs
      val explicitType = !isInferredTypeTree(vd.tpt)
      val ownedType = isOwnedType(sym.info) && !hasBorrowCaptures(sym.info) &&
        !sym.is(Lazy)
      val savedOwner = scopeOwner
      scopeOwner = sym
      try visitValDefIn(vd, sym, rhs, explicitType, ownedType)
      finally scopeOwner = savedOwner
    }

    private def visitValDefIn(
        vd: ValDef,
        sym: Symbol,
        rhs: Tree,
        explicitType: Boolean,
        ownedType: Boolean
    )(using scope: Scope): Unit = {
      if (ownedType && sym.is(Mutable)) {
        val owner = newOwner(sym, OwnerKind.Var, scope, sym.info)
        owner.initiallyOwned = !rhs.isEmpty && !isNullLiteral(rhs)
        if (!rhs.isEmpty)
          visitOwn(rhs, Some(owner), s"is stored in ${sym.name.show}")
      } else if (ownedType && takesOwnership(rhs, explicitType)) {
        val owner = newOwner(sym, OwnerKind.Local, scope, sym.info)
        visitOwn(rhs, Some(owner), s"is stored in ${sym.name.show}")
      } else {
        directOwners(rhs).toList match {
          case List(owner)
              if !sym.is(Mutable) && !sym.is(Lazy) &&
                leaves(rhs).forall(ownerOf(_).nonEmpty) =>
            // `val y = x` renames the owner `x`.
            aliases(sym) = owner
          case _ => ()
        }
        order += 1
        locals.getOrElseUpdate(sym, (scope, order))
        visit(rhs, Pos.Borrow)
        if (!aliases.contains(sym))
          retains(sym) = retains.getOrElse(sym, Set.empty) ++ retained(rhs)
      }
    }

    /** Does a value definition with the given right-hand side become an owner?
     *  Fresh values are always owned; values moved out of another owner only
     *  with an explicit type, as `val y = x` merely renames `x`.
     */
    private def takesOwnership(rhs: Tree, explicitType: Boolean): Boolean = {
      val ls = leaves(rhs).filterNot(l => isBottom(l.tpe) && !isNullLiteral(l))
      ls.nonEmpty && ls.forall { l =>
        isFresh(l) || isNullLiteral(l) ||
        (explicitType && (ownerOf(l).nonEmpty || fieldOf(l)
          .exists(f => isOwningField(f) && f.is(Mutable))))
      }
    }

    private def visitAssign(asg: Assign)(using scope: Scope): Unit = {
      val Assign(lhs, rhs) = asg
      val sym = lhs.symbol
      owners.get(sym) match {
        case Some(owner) if owner.kind == OwnerKind.Var =>
          ownerAssigns += asg
          visitOwn(rhs, Some(owner), s"is stored in ${sym.name.show}")
        case _ =>
          locals.get(sym) match {
            case Some((varScope, varOrder)) =>
              visit(rhs, Pos.Borrow)
              val borrowed = retained(rhs)
              borrowed.foreach { o =>
                if (!(varScope.isWithin(o.scope) && varOrder > o.order))
                  error(
                    s"${o.describe} does not live long enough: it is assigned to ${sym.name.show}, which outlives it",
                    rhs.srcPos
                  )
              }
              retains(sym) = retains.getOrElse(sym, Set.empty) ++ borrowed
            case None if isOwningField(sym) =>
              fieldAssigns += asg
              lhs match {
                case Select(qual, _) => visit(qual, Pos.Borrow)
                case _               => ()
              }
              visitOwn(rhs, None, s"is stored in field ${sym.name.show}")
            case None =>
              lhs match {
                case Select(qual, _) => visit(qual, Pos.Borrow)
                case _               => ()
              }
              if (isOwnedType(sym.info) &&
                  leaves(rhs).exists(l => ownerOf(l).nonEmpty))
                error(
                  s"Cannot move an owned value into ${sym.name.show}: only owning fields " +
                    "(fields of classes extending Owned) and local variables can own values",
                  rhs.srcPos
                )
              else visitEscape(rhs, s"is assigned to ${sym.name.show}")
          }
      }
    }

    // --------------------------------------------------------------------
    // Pass 2: rewriting

    private def flagOf(owner: Owner)(using Context): Symbol = {
      if (!owner.flag.exists)
        owner.flag = newSymbol(
          if (owner.kind == OwnerKind.Temp || owner.kind == OwnerKind.This ||
              owner.kind == OwnerKind.Param)
            owner.scope.owner
          else owner.sym.owner,
          UniqueName.fresh(
            (if (owner.kind == OwnerKind.This) "this"
             else owner.sym.name.toString)
              .concat("$owned")
              .toTermName
          ),
          Mutable | Synthetic,
          defn.BooleanType
        )
      owner.flag
    }

    private def ownerRef(owner: Owner)(using Context): Tree =
      if (owner.kind == OwnerKind.This) This(cls.asClass) else ref(owner.sym)

    private def dropCode(owner: Owner)(using Context): Tree = {
      val drop = destroyCall(ownerRef(owner))
      if (owner.needsFlag) If(ref(flagOf(owner)), drop, unitLiteral)
      else drop
    }

    private def flagDef(owner: Owner, initial: Boolean)(using
        Context
    ): List[Tree] =
      if (owner.needsFlag)
        List(ValDef(flagOf(owner).asTerm, Literal(Constant(initial))))
      else Nil

    private def setFlag(owner: Owner, value: Boolean)(using Context): Tree =
      Assign(ref(flagOf(owner)), Literal(Constant(value)))

    private def withDrop(owner: Owner, body: Tree)(using Context): Tree =
      Try(body, Nil, dropCode(owner))

    private def prepend(stats: List[Tree], tree: Tree)(using Context): Tree =
      if (stats.isEmpty) tree
      else
        tree match {
          case Block(ss, e) => Block(stats ++ ss, e)
          case _            => Block(stats, tree)
        }

    def rewrite()(using Context): Tree = {
      val body = rewriter.transform(dd.rhs)
      val wrapped = (thisOwner.toList ++ paramOwners).foldRight(body) {
        (owner, acc) =>
          prepend(flagDef(owner, initial = true), withDrop(owner, acc))
      }
      wrapTemps(root, wrapped)
    }

    private def wrapTemps(scope: Scope, body: Tree)(using Context): Tree =
      if (scope.temps.isEmpty) body
      else {
        val decls = scope.temps.toList.flatMap { t =>
          ValDef(t.sym.asTerm, defaultValue(t.tpe)) :: flagDef(
            t,
            initial = false
          )
        }
        val drops = scope.temps.toList.reverse.map(dropCode)
        Block(decls, Try(body, Nil, Block(drops, unitLiteral)))
      }

    private def defaultValue(tpe: Type)(using Context): Tree =
      Literal(Constant(null)).cast(tpe)

    private def freshLocal(name: String, tpe: Type)(using Context): TermSymbol =
      newSymbol(
        ctx.owner,
        UniqueName.fresh(name.toTermName),
        Synthetic,
        tpe.widen
      )

    private val rewriter: TreeMap = new TreeMap {
      override def transform(tree: Tree)(using Context): Tree = tree match {
        case _: DefDef | _: TypeDef => tree

        case _ if moves.contains(tree) =>
          Block(List(setFlag(ownerOf(tree).get, false)), tree)

        case _
            if temps.contains(tree) && temps(tree).scope.temps.contains(
              temps(tree)
            ) =>
          val temp = temps(tree)
          val value = super.transform(tree)
          Block(
            List(
              dropCode(temp),
              Assign(ref(temp.sym), value),
              setFlag(temp, true)
            ),
            ref(temp.sym)
          )

        case _ if discards.contains(tree) =>
          destroyCall(super.transform(tree))

        case _ if fieldTakes.contains(tree) =>
          val field = tree.symbol
          val qual = tree match {
            case Select(q, _) => transform(q)
            case _            => This(field.owner.asClass)
          }
          val qualSym = freshLocal("owned$obj", qual.tpe)
          val valueSym = freshLocal("owned$taken", tree.tpe)
          Block(
            List(
              ValDef(qualSym, qual.changeOwner(ctx.owner, qualSym)),
              ValDef(valueSym, ref(qualSym).select(field)),
              Assign(ref(qualSym).select(field), Literal(Constant(null)))
            ),
            ref(valueSym)
          )

        case asg @ Assign(lhs, rhs) if ownerAssigns.contains(asg) =>
          val owner = owners(lhs.symbol)
          val valueSym = freshLocal("owned$new", lhs.tpe)
          val value = transform(rhs).changeOwner(ctx.owner, valueSym)
          Block(
            List(
              ValDef(valueSym, value),
              dropCode(owner),
              Assign(ref(owner.sym), ref(valueSym)),
              setFlag(owner, true)
            ),
            unitLiteral
          )

        case asg @ Assign(lhs, rhs) if fieldAssigns.contains(asg) =>
          val field = lhs.symbol
          val qual = lhs match {
            case Select(q, _) => transform(q)
            case _            => This(field.owner.asClass)
          }
          val qualSym = freshLocal("owned$obj", qual.tpe)
          val valueSym = freshLocal("owned$new", field.info)
          val oldSym = freshLocal("owned$old", field.info)
          Block(
            List(
              ValDef(qualSym, qual.changeOwner(ctx.owner, qualSym)),
              ValDef(valueSym, transform(rhs).changeOwner(ctx.owner, valueSym)),
              ValDef(oldSym, ref(qualSym).select(field)),
              Assign(ref(qualSym).select(field), ref(valueSym)),
              destroyCall(ref(oldSym))
            ),
            unitLiteral
          )

        case block @ Block(stats, expr) =>
          val scope = scopes.get(block)
          val stats1 = stats.map(s => (s, transform(s)))
          val expr1 = transform(expr)
          def build(ss: List[(Tree, Tree)]): Tree = ss match {
            case Nil => expr1
            case (vd: ValDef, vd1) :: rest if owners.contains(vd.symbol) =>
              val owner = owners(vd.symbol)
              val initial = owner.kind != OwnerKind.Var || owner.initiallyOwned
              Block(
                vd1 :: flagDef(owner, initial),
                withDrop(owner, build(rest))
              )
            case (_, s1) :: rest => prepend(List(s1), build(rest))
          }
          val built =
            if (stats.exists(s => owners.contains(s.symbol))) build(stats1)
            else cpy.Block(block)(stats1.map(_._2), expr1)
          scope.fold(built)(wrapTemps(_, built))

        case inl @ Inlined(call, bindings, expansion) =>
          val scope = scopes.get(inl)
          val hasOwners =
            bindings.exists(b => owners.contains(b.symbol)) ||
              scope.exists(_.temps.nonEmpty)
          if (!hasOwners) super.transform(tree)
          else {
            val block = Block(bindings, expansion)
            scopes.get(inl).foreach(scopes(block) = _)
            cpy.Inlined(inl)(call, Nil, transform(block))
          }

        case _ => super.transform(tree)
      }
    }
  }

  // ---------------------------------------------------------------------
  // Application helpers

  /** Split a full application into the applied function and its argument lists.
   */
  private def splitApply(tree: Tree): (Tree, List[List[Tree]]) = tree match {
    case Apply(fn, args) =>
      val (core, argss) = splitApply(fn)
      (core, argss :+ args)
    case _ => (tree, Nil)
  }

  private def receiverOf(core: Tree): Option[Tree] = core match {
    case Select(qual, _)               => Some(qual)
    case TypeApply(Select(qual, _), _) => Some(qual)
    case _                             => None
  }

  /** For each argument, is it passed to a consume parameter? */
  private def consumeFlags(fn: Symbol, argss: List[List[Tree]])(using
      Context
  ): List[List[Boolean]] = {
    val paramss =
      if (!fn.exists || !fn.is(Method)) Nil
      else fn.paramSymss.filterNot(ps => ps.nonEmpty && ps.head.isType)
    argss.zipWithIndex.map { (args, i) =>
      val params = paramss.lift(i).getOrElse(Nil)
      args.indices.toList.map { j =>
        params.lift(j).exists(p => isConsume(p))
      }
    }
  }
}
