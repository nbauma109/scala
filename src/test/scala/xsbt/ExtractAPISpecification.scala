package xsbt

import xsbti.api._
import xsbt.api.SameAPI
import sbt.internal.util.UnitSpec

class ExtractAPISpecification extends UnitSpec {

  "Existential types in method signatures" should "have stable names" in stableExistentialNames()

  it should "extract children of a sealed class" in {
    def compileAndGetFooClassApi(src: String): ClassLike = {
      val compilerForTesting = new ScalaCompilerForUnitTesting
      val apis = compilerForTesting.extractApisFromSrc(src)
      val FooApi = apis.find(_.name() == "Foo").get
      FooApi
    }
    val src1 =
      """|sealed abstract class Foo
         |case class C1(x: Int) extends Foo
         |""".stripMargin
    val fooClassApi1 = compileAndGetFooClassApi(src1)
    val src2 =
      """|sealed abstract class Foo
         |case class C1(x: Int) extends Foo
         |case class C2(x: Int) extends Foo
         |""".stripMargin
    val fooClassApi2 = compileAndGetFooClassApi(src2)
    assert(SameAPI(fooClassApi1, fooClassApi2) !== true)
  }

  it should "extract correctly the definition type of a package object" in {
    val src = "package object foo".stripMargin
    val compilerForTesting = new ScalaCompilerForUnitTesting
    val apis = compilerForTesting.extractApisFromSrc(src)
    val Seq(fooClassApi) = apis.toSeq
    assert(fooClassApi.definitionType === DefinitionType.PackageModule)
  }

  it should "extract nested classes" in {
    val src =
      """class A {
        |  class B
        |}""".stripMargin
    val compilerForTesting = new ScalaCompilerForUnitTesting
    val apis = compilerForTesting.extractApisFromSrc(src).map(c => c.name -> c).toMap
    assert(apis.keys === Set("A", "A.B"))
  }

  it should "not extract local classes" in {
    val src =
      """class A
        |class B
        |class C { def foo: Unit = { class Inner2 extends B } }
        |class D { def foo: Unit = { new B {} } }""".stripMargin
    val compilerForTesting = new ScalaCompilerForUnitTesting
    val apis = compilerForTesting.extractApisFromSrc(src).map(c => c.name -> c).toMap
    assert(apis.keys === Set("A", "B", "C", "D"))
  }

  it should "extract flat (without members) api for a nested class" in {
    def compileAndGetFooClassApi(src: String): ClassLike = {
      val compilerForTesting = new ScalaCompilerForUnitTesting
      val apis = compilerForTesting.extractApisFromSrc(src)
      val FooApi = apis.find(_.name() == "Foo").get
      FooApi
    }
    val src1 =
      """class Foo {
        |  class A
        |}""".stripMargin
    val fooClassApi1 = compileAndGetFooClassApi(src1)
    val src2 =
      """class Foo {
        |  class A {
        |    def foo: Int = 123
        |  }
        |}""".stripMargin
    val fooClassApi2 = compileAndGetFooClassApi(src2)
    assert(SameAPI(fooClassApi1, fooClassApi2) === true)
  }

  it should "extract private classes" in {
    val src =
      """private class A
        |class B { private class Inner1 extends A }
        |""".stripMargin
    val compilerForTesting = new ScalaCompilerForUnitTesting
    val apis = compilerForTesting.extractApisFromSrc(src).map(c => c.name -> c).toMap
    assert(apis.keys === Set("A", "B", "B.Inner1"))
  }

  def stableExistentialNames(): Unit = {
    def compileAndGetFooMethodApi(src: String): Def = {
      val compilerForTesting = new ScalaCompilerForUnitTesting
      val sourceApi = compilerForTesting.extractApisFromSrc(src)
      val FooApi = sourceApi.find(_.name() == "Foo").get
      val fooMethodApi = FooApi.structure().declared().find(_.name == "foo").get
      fooMethodApi.asInstanceOf[Def]
    }
    val src1 = """
				|class Box[T]
				|class Foo {
				|	def foo: Box[_] = null
				|
				}""".stripMargin
    val fooMethodApi1 = compileAndGetFooMethodApi(src1)
    val src2 = """
				|class Box[T]
				|class Foo {
			    |   def bar: Box[_] = null
				|	def foo: Box[_] = null
				|
				}""".stripMargin
    val fooMethodApi2 = compileAndGetFooMethodApi(src2)
    assert(SameAPI.apply(fooMethodApi1, fooMethodApi2), "APIs are not the same.")
  }

  /**
   * Checks if representation of the inherited Namer class (with a declared self variable) in Global.Foo
   * is stable between compiling from source and unpickling. We compare extracted APIs of Global when Global
   * is compiled together with Namers or Namers is compiled first and then Global refers
   * to Namers by unpickling types from class files.
   */
  it should "make a stable representation of a self variable that has no self type" in pendingUntilFixed {
    def selectNamer(apis: Set[ClassLike]): ClassLike = {
      def selectClass(defs: Iterable[Definition], name: String): ClassLike = defs.collectFirst {
        case cls: ClassLike if cls.name == name => cls
      }.get
      val global = apis.find(_.name == "Global").get
      //val foo = selectClass(global.structure.declared, "Global.Foo")
      val foo = apis.find(_.name == "Global.Foo").get
      selectClass(foo.structure.inherited, "Namers.Namer")
    }
    val src1 =
      """|class Namers {
        |  class Namer { thisNamer => }
        |}
        |""".stripMargin
    val src2 =
      """|class Global {
        |  class Foo extends Namers
        |}
        |""".stripMargin
    val compilerForTesting = new ScalaCompilerForUnitTesting
    val apis = compilerForTesting.extractApisFromSrcs(reuseCompilerInstance = false)(List(src1, src2), List(src2))
    val _ :: src2Api1 :: src2Api2 :: Nil = apis.toList
    val namerApi1 = selectNamer(src2Api1)
    val namerApi2 = selectNamer(src2Api2)
    assert(SameAPI(namerApi1, namerApi2))
  }
}
