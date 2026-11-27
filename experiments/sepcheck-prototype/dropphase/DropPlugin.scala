package ownership.plugin

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.cc.SepCheck
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.UniqueName
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

/** Inserts Rust-style drops from what separation checking exported
 *  (`SepCheck.OwnerDrops`). It does no analysis of its own: every owner
 *  that is not moved by the result of its scope gets a flag, cleared at its
 *  move sites, and is dropped in a `finally` at the end of the scope, in
 *  reverse declaration order, by calling `ownership.Drop.destroy`.
 */
class DropPlugin extends StandardPlugin:
  val name = "ownership-drops"
  override val description = "insert drops of owned values"
  override def init(options: List[String]): List[PluginPhase] = List(DropPhase())

class DropPhase extends PluginPhase:
  val phaseName = "ownershipDrops"
  override val runsAfter = Set("cc")
  override val runsBefore = Set("elimOpaque")

  private def destroy(using Context): Symbol =
    requiredModule("ownership.Drop").requiredMethod("destroy")

  private def droppedOwners(tree: Tree)(using Context): List[SepCheck.OwnerDrop] =
    tree.getAttachment(SepCheck.OwnerDrops).getOrElse(Nil).filterNot(_.movedByResult)

  /** Flags for owners with move sites, and the rewrite that clears them. */
  private class Flags(owners: List[SepCheck.OwnerDrop])(using Context):
    val flags: Map[Symbol, Symbol] = owners.filter(_.moveSites.nonEmpty).map: o =>
      o.sym -> newSymbol(o.sym.owner, UniqueName.fresh(s"${o.sym.name}$$live".toTermName),
        Mutable | Synthetic, defn.BooleanType)
    .toMap
    private val sites = java.util.IdentityHashMap[Tree, Symbol]()
    for o <- owners; s <- o.moveSites; f <- flags.get(o.sym) do sites.put(s, f)

    def flagDef(sym: Symbol): List[Tree] =
      flags.get(sym).toList.map(f => ValDef(f.asTerm, Literal(Constant(true))))

    def dropCode(sym: Symbol): Tree =
      val drop = ref(destroy).appliedTo(ref(sym))
      flags.get(sym) match
        case Some(f) => If(ref(f), drop, unitLiteral)
        case None => drop

    val clearAtSites: TreeMap = new TreeMap:
      override def transform(t: Tree)(using Context): Tree =
        val f = sites.get(t)
        if f != null then Block(List(Assign(ref(f), Literal(Constant(false)))), t)
        else super.transform(t)

  override def transformBlock(tree: Block)(using Context): Tree =
    val owners = droppedOwners(tree)
    if owners.isEmpty then tree
    else
      val fl = Flags(owners)
      val ownerSyms = owners.map(_.sym).toSet
      val stats = tree.stats.map(fl.clearAtSites.transform(_))
      val expr = fl.clearAtSites.transform(tree.expr)
      def build(ss: List[Tree]): Tree = ss match
        case Nil => expr
        case (vd: ValDef) :: rest if ownerSyms.contains(vd.symbol) =>
          Block(vd :: fl.flagDef(vd.symbol), Try(build(rest), Nil, fl.dropCode(vd.symbol)))
        case s :: rest =>
          build(rest) match
            case Block(ss2, e) => Block(s :: ss2, e)
            case other => Block(List(s), other)
      build(stats)

  override def transformDefDef(tree: DefDef)(using Context): Tree =
    val owners = droppedOwners(tree)
    if owners.isEmpty || tree.rhs.isEmpty then tree
    else
      val fl = Flags(owners)
      val body = owners.reverse.foldLeft(fl.clearAtSites.transform(tree.rhs)): (acc, o) =>
        Try(acc, Nil, fl.dropCode(o.sym))
      val flagDefs = owners.flatMap(o => fl.flagDef(o.sym))
      cpy.DefDef(tree)(rhs = if flagDefs.isEmpty then body else Block(flagDefs, body))
