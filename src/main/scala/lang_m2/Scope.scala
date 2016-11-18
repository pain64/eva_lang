package lang_m2

import lang_m2.Ast0.{ParseNode, TypeHint}
import lang_m2.TypeCheckerUtil.Type

import scala.collection.mutable

/**
  * Created by over on 25.09.16.
  */
case class SymbolInfo(location: SymbolLocation, stype: Type, isMutable: Boolean)
case class SymbolKey(name: String, classifier: Option[TypeHint])

sealed trait SymbolLocation {
  val lowName: String
}
sealed trait ClosurableLocation
case class LocalSymbol(lowName: String) extends SymbolLocation with ClosurableLocation
case class ParamSymbol(lowName: String) extends SymbolLocation with ClosurableLocation
case class ClosureSymbol(lowName: String) extends SymbolLocation with ClosurableLocation
case class GlobalSymbol(lowName: String) extends SymbolLocation

sealed trait Scope {
  val parent: Option[Scope]
  val children = mutable.ListBuffer[Scope]()

  def mkChild[T <: Scope](onParent: Scope => T): T = {
    val child = onParent(this)
    children += child
    child
  }

  val vars: mutable.HashMap[String, SymbolInfo]

  def addVar(node: ParseNode, name: String, vtype: Type, isMutable: Boolean, location: SymbolLocation) = {
    if (vars.contains(name)) throw new CompileEx(node, CE.AlreadyDefined(name))
    vars += (name -> SymbolInfo(location, vtype, isMutable))
  }

  def findVar(name: String): Option[SymbolInfo]

  def traceVars: Iterable[SymbolInfo] = {
    vars.values ++ children.flatMap(_.vars.values)
  }
}

case class BlockScope(parent: Option[Scope], vars: mutable.HashMap[String, SymbolInfo] = mutable.HashMap()) extends Scope {
  def findVar(name: String): Option[SymbolInfo] = {
    var found: Option[SymbolInfo] = vars.get(name)
    if (found == None)
      parent.map(parent => found = parent.findVar(name))

    found
  }
}

case class FnScope(parent: Option[Scope], vars: mutable.HashMap[String, SymbolInfo] = mutable.HashMap()) extends Scope {
  val closuredVars = mutable.ListBuffer[SymbolInfo]()
  def findVar(name: String): Option[SymbolInfo] = {
    var found: Option[SymbolInfo] = vars.get(name)
    if (found == None)
      parent.map { parent =>
        found = parent.findVar(name).map { si =>
          if (!closuredVars.contains(si))
            closuredVars += si
          SymbolInfo(ClosureSymbol(si.location.lowName), si.stype, si.isMutable)
        }
      }

    found
  }

  def closured(closureTypeName: String): Seq[SymbolInfo] = closuredVars
}
