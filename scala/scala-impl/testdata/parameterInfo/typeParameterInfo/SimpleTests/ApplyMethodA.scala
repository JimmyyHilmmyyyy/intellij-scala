class A {
  def apply[a] = 0
}

object B extends A

B[Int<caret>]
//TEXT: a, STRIKEOUT: false