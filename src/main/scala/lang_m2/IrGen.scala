package lang_m2

import java.io.OutputStream

import lang_m2.Ast0.LlInline
import lang_m2.Ast1._

import scala.collection.mutable

case class IrGen(val out: OutputStream) {
  implicit class Printer(out: OutputStream) {
    def println(line: String) = {
      out.write(line.getBytes)
      out.write(10) // \n
    }
  }

  var tmpVars = 0
  var labelId = 0

  def nextTmpVar(needPercent: Boolean = true): String = {
    tmpVars += 1
    if (needPercent)
      "%" + tmpVars
    else tmpVars.toString
  }

  def nextLabel: String = {
    labelId += 1
    s"label$labelId"
  }

  def evalGep(baseType: Type, fields: Seq[String]): (Type, Seq[Int]) = {
    fields.foldLeft((baseType, Seq[Int](0))) {
      case ((_type, seq), field) =>
        val (f, idx) =
          _type.asInstanceOf[Struct].fields.zipWithIndex.find {
            case (f, idx) => f.name == field
          }.head
        (f._type, seq ++ Seq(idx))
    }
  }

  def zalloc(varName: String, varType: Type): Unit = {
    varType match {
      case struct: Struct =>
        val (_size, size, memsetDest) = (nextTmpVar(), nextTmpVar(), nextTmpVar())
        out.println(s"\t$varName = alloca ${varType.name}, align 4")
        out.println(s"\t${_size} = getelementptr ${varType.name}, ${varType.name}* null, i32 1")
        out.println(s"\t$size = ptrtoint ${varType.name}* ${_size} to i64")
        out.println(s"\t$memsetDest = bitcast ${varType.name}* $varName to i8*")
        out.println(s"\tcall void @llvm.memset.p0i8.i64(i8* $memsetDest, i8 0, i64 $size, i32 4, i1 false)")
      case _ =>
        out.println(s"\t$varName = alloca ${varType.name}")
    }
  }

  def load(from: String, varType: Type) = {
    val next = nextTmpVar()
    out.println(s"\t$next = load ${varType.name}, ${varType.name}* $from")
    next
  }

  def store(from: String, to: String, varType: Type) =
    out.println(s"\tstore ${varType.name} $from, ${varType.name}* $to")

  def loadAndStore(from: String, to: String, varType: Type) = {
    val next = nextTmpVar()
    out.println(s"\t$next = load ${varType.name}, ${varType.name}* $from")
    out.println(s"\tstore ${varType.name} $next, ${varType.name}* $to")
  }

  def genericIdToPtr(functions: Seq[Fn], fnType: FnType, vars: Map[String, Type], id: lId): (String, Type) = id match {
    case lLocal(value) => ("%" + value, vars(value))
    case lParam(value) =>
      val varType = fnType.fnPointer.args.find { arg =>
        arg.name == value
      }.map { arg => arg._type }.get

      varType match {
        case s: Struct => ("%" + value, varType)
        case _ => throw new Exception("not implemented in ABI")
      }
    case lClosure(value) => fnType match {
      case Closure(typeName, _, vals) =>
        val (closured, index) = vals.zipWithIndex.find {
          case (cv, _) => cv.name == value
        }.get

        val fieldPtr = nextTmpVar()
        out.println(s"\t$fieldPtr = getelementptr %${typeName}, %${typeName}* %closure, i32 0, i32 ${index + 1}")

        closured match {
          //FIXME: use varType.ptr instead!
          case ClosureVal(lLocal(vName), varType) =>
            (load(fieldPtr, Scalar(varType.name + "*")), varType)
          case ClosureVal(lParam(vName), varType) =>
            if (varType.isInstanceOf[Struct])
              (load(fieldPtr, varType), varType)
            else
              (fieldPtr, varType)
        }
      case _ => throw new Exception("not implemented in ABI")
    }
    case _ => throw new Exception("not implemented in ABI")
  }

  def genericCallToValue(functions: Seq[Fn], fnType: FnType, vars: Map[String, Type], call: Call) = call match {
    case Call(id, args) =>
      val (toCall, fnPtr, closureArgs): (String, FnPointer, Seq[String]) = id match {
        case lLocal(value) =>
          val ftype = vars(value)
          ftype match {
            case fnPtr: FnPointer => (load("%" + value, fnPtr), fnPtr, Seq())
            case closure: Closure =>
              val realFnPointer = closure.realFnPointer
              val ptr = nextTmpVar()
              out.println(s"\t$ptr = getelementptr %${closure.typeName}, %${closure.typeName}* %$value, i32 0, i32 0")
              (load(ptr, realFnPointer), realFnPointer, Seq(s"${closure.name}* %$value"))
            case _ => throw new Exception("not implemented in ABI")
          }
        case lParam(value) =>
          val ftype = fnType.fnPointer.args.find { arg =>
            arg.name == value
          }.map { arg => arg._type }.get

          ftype match {
            case fnPtr: FnPointer => ("%" + value, fnPtr, Seq())
            case ds@Disclosure(fnPointer) =>
              val realFnPtr = ds.realFnPointer
              val ptr = nextTmpVar()
              out.println(s"\t$ptr = getelementptr ${ds.name}, ${ds.name}* %$value, i32 0, i32 0")
              val casted = nextTmpVar()
              out.println(s"\t$casted = bitcast ${ds.name}* %$value to i8*")
              (load(ptr, realFnPtr), realFnPtr, Seq(s"i8* $casted"))
            case _ => throw new Exception("not implemented in ABI")
          }
        case lGlobal(value) =>
          val symbol = functions.find(_.name == value).get
          symbol._type match {
            case fnPtr: FnPointer => ("@" + escapeFnName(value), fnPtr, Seq())
            case _ => throw new Exception("not implemented in ABI")
          }
      }

      val calculatedArgs = (args).zip(fnPtr.args).map { case (arg, field) =>
        arg match {
          case id: lId =>
            field._type match {
              case s: Struct =>
                val (ptr, idType) = genericIdToPtr(functions, fnType, vars, id)
                ptr
              case ds: Disclosure =>
                val (ptr, idType) = genericIdToPtr(functions, fnType, vars, id)
                ptr
              case _ => genInitWithValue(functions, fnType, vars, field._type, id)
            }
          case other@_ => genInitWithValue(functions, fnType, vars, field._type, other)
        }
      }

      val joinedArgs = (calculatedArgs.zip(fnPtr.args).map {
        case (arg, argType) =>
          s"${if (argType._type.isInstanceOf[Struct]) argType._type.name + "*" else argType._type.name} $arg"
      } ++ closureArgs).mkString(", ")

      if (fnPtr.ret == Scalar("void")) {
        out.println(s"\tcall ${fnPtr.ret.name} ${toCall}($joinedArgs)")
        null
      } else {
        val tmp = nextTmpVar()
        out.println(s"\t$tmp = call ${fnPtr.ret.name} ${toCall}($joinedArgs)")
        tmp
      }
  }

  // FIXME: нужен ли vtype?
  def genInitWithValue(functions: Seq[Fn], fnType: FnType, vars: Map[String, Type], vtype: Type, init: Init): String = init match {
    case lInt(value) => value
    case lFloat(value) =>
      if (vtype.name == "float") {
        val d = (new java.lang.Float(value)).toDouble
        "0x" + java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(d))
      } else value
    case lString(name, value) =>
      val tmp1 = nextTmpVar()
      out.println(s"\t$tmp1 = bitcast [${value.bytesLen} x i8]* @.$name to i8*")
      tmp1
    case lGlobal(value) => "@" + value
    case lLocal(varName) =>
      vtype match {
        case ds: Disclosure =>
          val tmp = nextTmpVar()
          val sourceType = vars(varName)
          out.println(s"\t$tmp = bitcast ${sourceType.name}* %$varName to ${vtype.name}*")
          tmp
        case _ => load(s"%$varName", vtype)
      }
    case lParam(paramName) =>
      if (vtype.isInstanceOf[Struct]) load(s"%$paramName", vtype)
      else s"%$paramName"
    case closure: lClosure =>
      val (ptr, vtype) = genericIdToPtr(functions, fnType, vars, closure)
      load(ptr, vtype)
    case a: Access =>
      val (ptr, vtype) = genericIdToPtr(functions, fnType, vars, a.from)
      val (resType, indicies) = evalGep(vtype, Seq(a.prop))
      val res = nextTmpVar()

      out.println(s"\t$res = getelementptr ${vtype.name}, ${vtype.name}* $ptr, ${indicies.map { i => s"i32 $i" } mkString (", ")}")
      load(res, resType)
    case call: Call =>
      genericCallToValue(functions, fnType, vars, call)
    case BoolAnd(left, right) =>
      val (beginLabel, secondLabel, endLabel) = (nextLabel, nextLabel, nextLabel)
      out.println(s"\tbr label %$beginLabel")
      out.println(s"$beginLabel:")
      val leftRes = genInitWithValue(functions, fnType, vars, Scalar("i1"), left)
      out.println(s"\tbr i1 $leftRes, label %$secondLabel, label %$endLabel")

      out.println(s"$secondLabel:")
      val rightRes = genInitWithValue(functions, fnType, vars, Scalar("i1"), right)
      out.println(s"\tbr label %$endLabel")

      out.println(s"$endLabel:")
      val tmp1 = nextTmpVar()
      out.println(s"\t$tmp1 = phi i1 [false, %$beginLabel], [$rightRes, %$secondLabel]")

      tmp1
    case BoolOr(left, right) =>
      //FIXME: deduplicate code
      val (beginLabel, secondLabel, endLabel) = (nextLabel, nextLabel, nextLabel)
      out.println(s"\tbr label %$beginLabel")
      out.println(s"$beginLabel:")
      val leftRes = genInitWithValue(functions, fnType, vars, Scalar("i1"), left)
      out.println(s"\tbr i1 $leftRes, label %$endLabel, label %$secondLabel")

      out.println(s"$secondLabel:")
      val rightRes = genInitWithValue(functions, fnType, vars, Scalar("i1"), right)
      out.println(s"\tbr label %$endLabel")

      out.println(s"$endLabel:")
      val tmp1 = nextTmpVar()
      out.println(s"\t$tmp1 = phi i1 [true, %$beginLabel], [$rightRes, %$secondLabel]")

      tmp1
  }

  def genStat(functions: Seq[Fn], fnType: FnType, vars: Map[String, Type], seq: Seq[Stat]): Unit =
    seq.foreach {
      case Store(to, fields, init) =>
        val (base, baseType) = genericIdToPtr(functions, fnType, vars, to)
        val (ptr, resType) =
          if (fields.length == 0) (base, baseType)
          else {
            val ptr = nextTmpVar()
            val (resType, indicies) = evalGep(baseType, fields)
            out.println(s"\t$ptr = getelementptr ${baseType.name}, ${baseType.name}* ${base}, ${indicies.map { i => s"i32 $i" } mkString (", ")}")
            (ptr, resType)
          }

        val forStore = genInitWithValue(functions, fnType, vars, resType, init)
        store(forStore, ptr, resType)
      case StoreEnclosure(to, init) =>
        val (base, baseType) = genericIdToPtr(functions, fnType, vars, to)
        baseType match {
          case closure@Closure(typeName, fnPtr, vals) =>
            // store fn pointer
            val realFnPtr = closure.realFnPointer
            val ptrToFn = nextTmpVar()
            out.println(s"\t$ptrToFn = getelementptr %$typeName, %$typeName* $base, i32 0, i32 0")
            val fnPtrValue = genInitWithValue(functions, fnType, vars, fnPtr, init)
            store(fnPtrValue, ptrToFn, realFnPtr)

            // store all closured vals
            vals.zipWithIndex.foreach {
              case (v, index) =>
                val ptrToClosureVal = nextTmpVar()
                out.println(s"\t$ptrToClosureVal = getelementptr %$typeName, %$typeName* $base, i32 0, i32 ${index + 1}")
                val (closureValPtr, _) = genericIdToPtr(functions, fnType, vars, v.closurable)
                store(closureValPtr, ptrToClosureVal, Scalar(v.varType.name + "*"))
            }
          case _ => throw new Exception("not implemented in ABI")
        }
      case call: Call =>
        genericCallToValue(functions, fnType, vars, call)
      case boolAnd: BoolAnd =>
        genInitWithValue(functions, fnType, vars, Scalar("i1"), boolAnd)
      case boolOr: BoolOr =>
        genInitWithValue(functions, fnType, vars, Scalar("i1"), boolOr)
      case Cond(init, _if, _else) =>
        val condVar = genInitWithValue(functions, fnType, vars, Scalar("i1"), init)
        val (ifLabel, elseLabel, endLabel) = (nextLabel, nextLabel, nextLabel)

        out.println(s"\tbr i1 $condVar, label %$ifLabel, label %$elseLabel")
        out.println(s"$ifLabel:")
        genStat(functions, fnType, vars, _if)
        out.println(s"\tbr label %$endLabel")
        out.println(s"$elseLabel:")
        genStat(functions, fnType, vars, _else)
        out.println(s"\tbr label %$endLabel")
        out.println(s"$endLabel:")
      case While(init, seq) =>
        val (beginLabel, bodyLabel, endLabel) = (nextLabel, nextLabel, nextLabel)

        out.println(s"\tbr label %$beginLabel")
        out.println(s"$beginLabel:")

        val condVar = genInitWithValue(functions, fnType, vars, Scalar("i1"), init)
        out.println(s"\tbr i1 $condVar, label %$bodyLabel, label %$endLabel")
        out.println(s"$bodyLabel:")

        genStat(functions, fnType, vars, seq)

        out.println(s"\tbr label %$beginLabel")
        out.println(s"$endLabel:")

      case Ret(init) =>
        val retType = fnType.fnPointer.realRet
        out.println(s"\tret ${retType.name} ${genInitWithValue(functions, fnType, vars, retType, init)}")
      case RetVoid() =>
        out.println(s"\tret void")
    }

  def genFunction(functions: Seq[Fn], fn: Fn): Unit = {
    fn._type match {
      case fnPtr@FnPointer(args, ret) =>
        out.println(s"define ${fnPtr.realRet.name} @${escapeFnName(fn.name)}(${fnPtr.irArgs.mkString(", ")}) {")
      case Closure(typeName, fnPtr, vals) =>
        val realFnPtr = FnPointer(fnPtr.args :+ Field("closure", Scalar(s"%$typeName*")), fnPtr.ret)
        val mappedClosure = (realFnPtr.name +: vals.map { cv =>
          cv.varType.name + "*"
        }).mkString(", ")

        out.println(s"%$typeName = type { $mappedClosure }")
        out.println(s"define ${realFnPtr.ret.name} @${fn.name}(${realFnPtr.irArgs.mkString(", ")}) {")
      case Disclosure(fnPointer) => throw new Exception("not implemented in ABI")
    }

    fn.body match {
      case IrInline(ir) => out.println(ir)
      case Block(vars, stats) =>
        vars.foreach {
          case (varName, varType) => zalloc("%" + varName, varType)
        }
        genStat(functions, fn._type, vars, stats)
    }

    out.println("}")
  }

  def gen(module: Module): Unit = {
    println(module)
    out.println("declare i32 @memcmp(i8*, i8*, i64)")
    out.println("declare void @llvm.memset.p0i8.i64(i8* nocapture, i8, i64, i32, i1)")

    module.headers.foreach { header =>
      val signature = header._type
      out.println(s"declare ${signature.realRet.name} @${escapeFnName(header.name)}(${signature.irArgs.mkString(", ")})")
    }

    module.structs.foreach { struct =>
      out.println(s"${struct.name} = type { ${struct.fields.map { f => f._type.name }.mkString(", ")} }")
    }

    val (consts, functions1) = IrPasses.inferStringConsts(module.functions)


    consts.foreach { const =>
      out.println(s"@.${const.name} = private constant [${const.value.bytesLen} x i8] ${"c\"" + const.value.str + "\""}, align 1")
    }
    functions1.foreach {
      fn => tmpVars = 0; genFunction(functions1, fn)
    }
  }
}