package codegen2

import codegen2.CodeGenUtil.compile
import org.scalatest.FunSuite

class _15UnlessTest extends FunSuite {
  test("unless: simple") {
    compile(
      """
         def > = self: Int, other: Int do llvm
           %1 = icmp sgt i32 %self, %other
           %2 = zext i1 %1 to i8
           ret i8 %2 .Bool

         def main =
           x: Int | String | Bool = 42
           x unless
             is i: Int do i > 5
             is String do false ..
      """)
  }
}
