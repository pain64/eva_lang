package lang_m2

import lang_m2.Ast0._
import TypeCheckerUtil.{thBool, toLow}

/**
  * Created by over on 23.08.16.
  */
object Macro {
  def genConstructor(_package: String, typeMap: Map[ScalarTypeHint, Type], td: FactorType): Fn = {
    var nextId = 0
    val th = FnTypeHint(td.fields.map { field =>
      FnTypeHintField(field.name, field.typeHint)
    }, ScalarTypeHint(td.name, _package))

    Fn(td.name, Some(th), LlInline(
      (td.fields.map { field =>
        nextId += 1
        val lowTh = toLow(typeMap, field.typeHint).name

        s"\t%$nextId = getelementptr %struct.${td.name}, %struct.${td.name}* %ret, i32 0, i32 ${nextId - 1}\n" +
          s"\tstore $lowTh %${field.name}, $lowTh* %$nextId"
      } :+ "\tret void").mkString("\n")
    ), None)
  }

  def genEquals(_package: String, typeMap: Map[ScalarTypeHint, Type], td: FactorType): Fn = {
    val th = FnTypeHint(Seq(FnTypeHintField("self", ScalarTypeHint(td.name, _package)), FnTypeHintField("other", ScalarTypeHint(td.name, _package))),
      thBool)
    val lowTh = toLow(typeMap, ScalarTypeHint(td.name, _package)).name

    Fn("==", Some(th), LlInline(
      s"""
         |    %1 = getelementptr $lowTh, $lowTh* null, i32 1
         |    %2 = ptrtoint $lowTh* %1 to i64
         |    %3 = bitcast $lowTh* %self to i8*
         |    %4 = bitcast $lowTh* %other to i8*
         |    %5 = call i32 @memcmp(i8* %3, i8* %4, i64 %2)
         |    %6 = icmp eq i32 %5, 0
         |    ret i1 %6
      """.stripMargin
    ), None)
  }
  def genNotEquals(_package: String, typeMap: Map[ScalarTypeHint, Type], td: FactorType): Fn = {
    val _thBool = Some(thBool)
    val thSelf = Some(ScalarTypeHint(td.name, _package))

    Fn("!=", None, Block(Seq(FnArg("self", thSelf), FnArg("other", thSelf)), Seq(
      SelfCall("!", SelfCall("==", lId("self"), Seq(lId("other"))), Seq())
    )), _thBool)
  }

  def booleanNot(): Fn = {
    val th = FnTypeHint(Seq(FnTypeHintField("self", thBool)), thBool)
    Fn("!", Some(th), LlInline(
      """
        |   %1 = xor i1 %self, 1
        |   ret i1 %1
      """.stripMargin), None)
  }
}
