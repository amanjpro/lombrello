//
//     _____   .__         .__ ___.                    .__ scala-miniboxing.org
//    /     \  |__|  ____  |__|\_ |__    ____  ___  ___|__|  ____     ____
//   /  \ /  \ |  | /    \ |  | | __ \  /  _ \ \  \/  /|  | /    \   / ___\
//  /    Y    \|  ||   |  \|  | | \_\ \(  <_> ) >    < |  ||   |  \ / /_/  >
//  \____|__  /|__||___|  /|__| |___  / \____/ /__/\_ \|__||___|  / \___  /
//          \/          \/          \/               \/         \/ /_____/
// Copyright (c) 2012-2014 Scala Team, École polytechnique fédérale de Lausanne
//
package miniboxing.plugin

import ch.usi.inf.l3.lombrello.neve.NeveDSL._
import scala.tools.nsc.Global
import scala.tools.nsc.Phase
import scala.tools.nsc.plugins._
import scala.tools.nsc.transform._
import metadata._
import transform._
import inject._
import coerce._
import commit._
import hijack._
import interop.inject._
import interop.coerce._
import interop.commit._

/** Specialization hijacking component `@specialized T -> @miniboxed T` */
trait HijackComponent extends
    PluginComponent
    with MiniboxInfoHijack
    with MiniboxDefinitions
    with ScalacCrossCompilingLayer {

  def flag_hijack_spec: Boolean
  def flag_mark_all: Boolean
}

/** Glue transformation to bridge Function and MiniboxedFunction */
trait InteropInjectComponent extends
    PluginComponent
    with InteropDefinitions
    with InteropInjectInfoTransformer
    with InteropInjectTreeTransformer
    with ScalacCrossCompilingLayer {

  def interopInjectPhase: StdPhase

  def afterInteropInject[T](op: => T): T = global.afterPhase(interopInjectPhase)(op)
  def beforeInteropInject[T](op: => T): T = global.beforePhase(interopInjectPhase)(op)

  def flag_rewire_functionX: Boolean
  def flag_rewire_functionX_bridges: Boolean
}

/** Glue transformation to bridge Function and MiniboxedFunction */
trait InteropCoerceComponent extends
    PluginComponent
    with InteropCoerceTreeTransformer
    with InteropAnnotationCheckers
    with ScalacCrossCompilingLayer {

  val interop: InteropInjectComponent { val global: InteropCoerceComponent.this.global.type }

  def interopCoercePhase: StdPhase

  def flag_strict_typechecking: Boolean

  def afterInteropCoerce[T](op: => T): T = global.afterPhase(interopCoercePhase)(op)
  def beforeInteropCoerce[T](op: => T): T = global.beforePhase(interopCoercePhase)(op)
}

/** Glue transformation to bridge Function and MiniboxedFunction */
trait InteropCommitComponent extends
    PluginComponent
    with InteropCommitInfoTransformer
    with InteropCommitTreeTransformer
    with ScalacCrossCompilingLayer {

  val interop: InteropInjectComponent { val global: InteropCommitComponent.this.global.type }

  def interopCommitPhase: StdPhase

  def afterInteropCommit[T](op: => T): T = global.afterPhase(interopCommitPhase)(op)
  def beforeInteropCommit[T](op: => T): T = global.beforePhase(interopCommitPhase)(op)
}

/** Injecticator component `def t -> def t_L, def t_J` */
trait MiniboxInjectComponent extends
    PluginComponent
    with MiniboxLogging
    with MiniboxDefinitions
    with MiniboxNameUtils
    with MiniboxMetadata
    with MiniboxMetadataUtils
    with MiniboxMetadataAddons
    with MiniboxMethodInfo
    with MiniboxInjectInfoTransformation
    with MiniboxInjectTreeTransformation
    with TreeRewriters
    with ScalacCrossCompilingLayer {

  def mboxInjectPhase: StdPhase

  def afterMiniboxInject[T](op: => T): T = global.afterPhase(mboxInjectPhase)(op)
  def beforeMiniboxInject[T](op: => T): T = global.beforePhase(mboxInjectPhase)(op)

  def flag_log: Boolean
  def flag_debug: Boolean
  def flag_stats: Boolean
  def flag_spec_no_opt: Boolean
  def flag_loader_friendly: Boolean
  def flag_two_way: Boolean
}

/** Introduces explicit Coerceations from `T` to `@storage T` and back */
trait MiniboxCoerceComponent extends
    PluginComponent
    with MiniboxCoerceTreeTransformer
    with MiniboxAnnotationCheckers
    with ScalacCrossCompilingLayer {

  val minibox: MiniboxInjectComponent { val global: MiniboxCoerceComponent.this.global.type }

  def mboxCoercePhase: StdPhase

  def flag_strict_typechecking: Boolean

  def afterMiniboxCoerce[T](op: => T): T = global.afterPhase(mboxCoercePhase)(op)
  def beforeMiniboxCoerce[T](op: => T): T = global.beforePhase(mboxCoercePhase)(op)
}


/** Specializer component `T @storage -> Long` */
trait MiniboxCommitComponent extends
    PluginComponent
    with MiniboxCommitInfoTransformer
    with MiniboxCommitTreeTransformer
    with ScalacCrossCompilingLayer {

  val minibox: MiniboxInjectComponent { val global: MiniboxCommitComponent.this.global.type }
  val interop: InteropInjectComponent { val global: MiniboxCommitComponent.this.global.type }

  def mboxCommitPhase: StdPhase

  def afterMiniboxCommit[T](op: => T): T = global.afterPhase(mboxCommitPhase)(op)
  def beforeMiniboxCommit[T](op: => T): T = global.beforePhase(mboxCommitPhase)(op)

  def flag_log: Boolean
  def flag_debug: Boolean
  def flag_stats: Boolean
  def flag_two_way: Boolean
}

trait PreTyperComponent extends
  PluginComponent
  with TypingTransformers
  with ScalacCrossCompilingLayer {

  val miniboxing: MiniboxInjectComponent { val global: PreTyperComponent.this.global.type }
}

trait PostTyperComponent extends
  PluginComponent
  with TypingTransformers
  with ScalacCrossCompilingLayer {

  import global._
  import global.Flag._
  val miniboxing: MiniboxInjectComponent { val global: PostTyperComponent.this.global.type }
}

/** Main miniboxing class */
@plugin(PreTyperPhase,
        PostTyperPhase,
        InteropInjectPhase,
        InteropCoercePhase,
        InteropCommitPhase,
        HijackPhase,
        MiniboxInjectPhase,
        MiniboxCoercePhase,
        MiniboxCommitPhase) class Minibox {

  import global._

  val name = "minibox"
  describe("Specializes generic classes")

  // lazy val components = {
  //   if (!flag_no_logo) {

  //     def printLogo() =
  //       Console.println("""
  //         |     _____   .__         .__ ____.                     .__ scala-miniboxing.org
  //         |    /     \  |__|  ____  |__|\_  |__    _____  ___  ___|__|  ____    _____
  //         |   /  \ /  \ |  | /    \ |  | |  __ \  /  ___\ \  \/  /|  | /    \  /  ___\
  //         |  /    Y    \|  ||   |  \|  | |  \_\ \(  (_)  ) >    < |  ||   |  \(  /_/  )
  //         |  \____|__  /|__||___|  /|__| |____  / \_____/ /__/\_ \|__||___|  / \___  /
  //         |          \/          \/           \/                \/         \/ /_____/
  //         | Copyright (c) 2012-2014 Scala Team, École polytechnique fédérale de Lausanne.""".stripMargin)

  //     // printLogo()
  //   }

  //   // and here are the compiler phases miniboxing introduces:
  //   List[PluginComponent](PreTyperPhase,
  //                         PostTyperPhase,
  //                         InteropInjectPhase,
  //                         InteropCoercePhase,
  //                         InteropCommitPhase,
  //                         HijackPhase,
  //                         MiniboxInjectPhase,
  //                         MiniboxCoercePhase,
  //                         MiniboxCommitPhase)
  // }

  // LDL Coercions
  addAnnotationChecker(MiniboxCoercePhase.StorageAnnotationChecker)
  addAnnotationChecker(InteropCoercePhase.mbFunctionAnnotationChecker)

  var flag_log = sys.props.get("miniboxing.log").isDefined
  var flag_debug = sys.props.get("miniboxing.debug").isDefined
  var flag_stats = sys.props.get("miniboxing.stats").isDefined
  var flag_hijack_spec = sys.props.get("miniboxing.hijack.spec").isDefined
  var flag_spec_no_opt = sys.props.get("miniboxing.Commit.no-opt").isDefined
  var flag_loader_friendly = sys.props.get("miniboxing.loader").isDefined
  var flag_no_logo = sys.props.get("miniboxing.no-logo").isDefined
  var flag_two_way = true
  var flag_rewire_functionX = true
  var flag_rewire_functionX_bridges = true
  var flag_mark_all = false // type parameters as @miniboxed
  var flag_strict_typechecking = false

  override def processOptions(options: List[String], error: String => Unit) {
    for (option <- options) {
      if (option.toLowerCase() == "log")
        flag_log = true
      else if (option.toLowerCase() == "debug")
        flag_debug = true
      else if (option.toLowerCase() == "stats")
        flag_stats = true
      else if (option.toLowerCase() == "hijack")
        flag_hijack_spec = true
      else if (option.toLowerCase() == "spec-no-opt")
        flag_spec_no_opt = true
      else if (option.toLowerCase() == "loader")
        flag_loader_friendly = true
      else if (option.toLowerCase() == "no-logo")
        flag_no_logo = true
      else if (option.toLowerCase() == "yone-way")   // Undocumented flag, only used for running the test suite,
        flag_two_way = false                         // where the tests required the one-way translation
      else if (option.toLowerCase() == "ygen-brdgs") // Undocumented flag, only used for running the test suite
        flag_rewire_functionX_bridges = false        // while avoiding func. to miniboxed func. bridge optimization
      else if (option.toLowerCase() == "ystrict-typechecking") // Undocumented flag
        flag_strict_typechecking = true
      else if (option.toLowerCase() == "library-functions")
        flag_rewire_functionX  = false
      else if (option.toLowerCase() == "two-way")
        global.warning("The two-way transformation (with long and double as storage types) has become default in " +
                       "version 0.4 version of the miniboxing plugin, so there is no need to specify it in the " +
                       "command line")
      else if (option.toLowerCase() == "mark-all")
        flag_mark_all = true
      else
        error("Miniboxing: Option not understood: " + option)
    }
  }

  override val optionsHelp: Option[String] = Some(Seq(
    s"  -P:${name}:log                 log miniboxing signature transformations",
    s"  -P:${name}:stats               log miniboxing tree transformations (verbose logging)",
    s"  -P:${name}:debug               debug logging for the miniboxing plugin (rarely used)",
    s"  -P:${name}:hijack              hijack the @specialized(...) notation for miniboxing",
    s"  -P:${name}:spec-no-opt         don't optimize method specialization, do create useless specializations\n",
    s"  -P:${name}:loader              generate classloader-friendly code (but more verbose)",
    s"  -P:${name}:no-logo             skip the miniboxing logo display",
    s"  -P:${name}:library-functions   do not rewrite scala.FunctionX to the optimized MiniboxedFunctionX (X=1,2,3)",
    s"  -P:${name}:mark-all            implicitly add @miniboxed annotations to all type parameters").mkString("\n"))

}

@phase("hijacker") class HijackPhase extends HijackComponent {
  rightAfter("extmethods")

  def flag_hijack_spec = Minibox.this.flag_hijack_spec
  def flag_two_way = Minibox.this.flag_two_way
  def flag_mark_all = Minibox.this.flag_mark_all

  // no change
  def transform(tree: Tree) = tree
}

@checker("interop-inject") class InteropInjectPhase 
        extends InteropInjectComponent {
  rightAfter("patmat")

  def flag_rewire_functionX: Boolean = Minibox.this.flag_rewire_functionX
  def flag_rewire_functionX_bridges  = Minibox.this.flag_rewire_functionX_bridges

  var interopInjectPhase : StdPhase = _
  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    interopInjectPhase = new Phase(prev)
    interopInjectPhase
  }
}

@checker("interop-coerce") class InteropCoercePhase extends {
  val interop: InteropInjectPhase.type = InteropInjectPhase
} with InteropCoerceComponent {
  rightAfter("uncurry")

  def flag_strict_typechecking = Minibox.this.flag_strict_typechecking

  var interopCoercePhase : StdPhase = _
  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    interopCoercePhase = 
        new CoercePhase(prev.asInstanceOf[InteropCoercePhase.this.Phase])
    interopCoercePhase
  }
}

@checker("interop-commit") class InteropCommitPhase extends {
  val interop: InteropInjectPhase.type = InteropInjectPhase
} with InteropCommitComponent {
  rightAfter(InteropCoercePhase.phaseName)

  var interopCommitPhase : StdPhase = _
  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    interopCommitPhase = new Phase(prev)
    interopCommitPhase
  }
}



@checker("-coerce") class MiniboxCoercePhase extends {
  val minibox: MiniboxInjectPhase.type = MiniboxInjectPhase
} with MiniboxCoerceComponent {

  rightAfter(MiniboxInjectPhase.phaseName)

  def flag_strict_typechecking = Minibox.this.flag_strict_typechecking

  var mboxCoercePhase : StdPhase = _
  def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    mboxCoercePhase = new CoercePhase(prev.asInstanceOf[minibox.Phase])
    mboxCoercePhase
  }
}

@checker("-commit") object MiniboxCommitPhase extends {
  val minibox: MiniboxInjectPhase.type = MiniboxInjectPhase
  val interop: InteropInjectPhase.type = InteropInjectPhase
} with MiniboxCommitComponent {
  rightAfter(MiniboxCoercePhase.phaseName)

  def flag_log = Minibox.this.flag_log
  def flag_debug = Minibox.this.flag_debug
  def flag_stats = Minibox.this.flag_stats
  def flag_two_way = Minibox.this.flag_two_way

  var mboxCommitPhase : StdPhase = _
  override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
    mboxCommitPhase = new Phase(prev)
    mboxCommitPhase
  }
}

@checker("mb-pretyper") object PreTyperPhase extends {
  val miniboxing: MiniboxInjectPhase.type = MiniboxInjectPhase
} with PreTyperComponent {
  rightAfter("parser")

  def check(unit: CompilationUnit) {
    for (sym <- miniboxing.metadata.allStemClasses)
      if (miniboxing.metadata.classStemTraitFlag(sym))
        sym.resetFlag(ABSTRACT)
      else
        sym.resetFlag(ABSTRACT | TRAIT)
  }
}


@checker("mb_posttyper") class PostTyperPhase extends {
    val miniboxing: MiniboxInjectPhase.type = MiniboxInjectPhase
  } with PreTyperComponent {
  after("typer")

  def check(unit: CompilationUnit) {
    for (sym <- miniboxing.metadata.allStemClasses)
      sym.setFlag(ABSTRACT | TRAIT)
  }
}

@phase("-inject") class MiniboxInjectPhase 
                  extends MiniboxInjectComponent {
  rightAfter(InteropCommitPhase.phaseName)

  def flag_log = Minibox.this.flag_log
  def flag_debug = Minibox.this.flag_debug
  def flag_stats = Minibox.this.flag_stats
  def flag_spec_no_opt = Minibox.this.flag_spec_no_opt
  def flag_loader_friendly = Minibox.this.flag_loader_friendly
  def flag_two_way = Minibox.this.flag_two_way

  var mboxInjectPhase : StdPhase = _
  // override def newPhase(prev: scala.tools.nsc.Phase): StdPhase = {
  //   mboxInjectPhase = new Phase(prev)
  //   mboxInjectPhase
  // }

  def transform(tree: Tree) = {
    // execute the tree transformer after all symbols have been processed
    val tree1 = afterMiniboxInject(new MiniboxTreeTransformer(unit).transform(tree))
    //tree1.foreach(tree => assert(tree.tpe != null, "tree not typed: " + tree))
    tree1
  }
}
