/*
 * Copyright (c) <2014>, Amanj Sherwany <http://www.amanj.me>
 * All rights reserved.
 * */

package ch.usi.inf.l3.kara.compiler


import ch.usi.inf.l3.lombrello.neve.NeveDSL._
import scala.reflect.internal.Flags._
import scala.Option.option2Iterable
import ch.usi.inf.l3.kara.KaraPlugin

/*
 * TODO: There exist numerous methods and functionalities that need to be put
 * in the Lombrello framework. Do it ASAP -- Amanj
 */
@phase("kara-mover") class ReadDependantExtractor {
  //  override val runsRightAfter = Some("kara-typer")
  //  val runsAfter = List[String]("kara-typer")
  //  override val runsRightAfter = Some("classFinder")
  after(List("classFinder"))

  before(List(PHASE_PATMAT))


  val karaClassName = "ch.usi.inf.l3.kara.runtime.KaraVariable"
  val karaRuntimeStr = "ch.usi.inf.l3.kara.runtime.KaraRuntime"
  val karaAnnotationName = "ch.usi.inf.l3.kara.quals.incremental"



  val karaClass = 
      rootMirror.getClassByName(newTypeName(karaClassName))
  val karaRuntime = 
      rootMirror.getClassByName(newTypeName(
                  karaRuntimeStr)).companionModule
  val readName = newTermName("read")
  val runClosureName = newTermName("runClosure")
  val karaAnnotation = getAnnotation(karaAnnotationName)

  private def hasRead(x: Tree): Boolean = {
    // As for If and Match, shall I announce ``read'' if only the condition has
    // it? mmm makes sense to some extend, but not sure
    x match {
      case Apply(mthd, args) =>
        hasRead(mthd) || hasRead(args)
      case TypeApply(qual, targs) => hasRead(qual)
      case Assign(_, rhs) => hasRead(rhs)
      case Block(stats, expr) => hasRead(stats) || hasRead(expr)
      case CaseDef(_, _, body) =>
        hasRead(body)
      case ClassDef(_, _, _, impl) => hasRead(impl)
      case ModuleDef(_, _, impl) => hasRead(impl)
      case DefDef(_, _, _, _, _, rhs) => hasRead(rhs)
      case Function(_, body) => hasRead(body)
      case id: Ident => hasAnnotation(id, karaAnnotation)
      case If(cond, thenp, elsep) => 
        hasRead(cond) || hasRead(thenp) || hasRead(elsep)
      case LabelDef(_, _, rhs) => hasRead(rhs)
      case Match(selector, cases) => hasRead(selector) || hasRead(cases)
      case PackageDef(_, stats) => hasRead(stats)
      case select @ Select(qual, name) =>
        hasAnnotation(select, karaAnnotation) || hasRead(qual)
      case Template(_, _, body) => hasRead(body)
      case Try(block, catches, finalizer) =>
        hasRead(block) || hasRead(catches) || hasRead(finalizer)
      case ValDef(_, _, _, rhs) => hasRead(rhs)
      case _ =>
        false
    }
    //    def hasReadAux(y: Tree): Boolean = {
    //
    //      x.exists((y) => {
    //        y match {
    //          case Assign(lhs, rhs) if hasRead(rhs) => true
    //          case id: Ident if hasAnnotation(id, karaAnnotation) => true
    //          case id: Ident => false
    //          case t => hasRead(t)
    //          //      case Select(qual, name) if (name == readName) => true
    //          //          case _ => false
    //        }
    //      })
    //    }
    //    x match {
    //      case Assign(lhs, rhs) => hasReadAux(rhs)
    //      case Block(stats, expr) =>
    //        hasRead(stats) || hasRead(expr)
    //      case id: Ident if hasAnnotation(id, karaAnnotation) => true
    //      case id: Ident => false
    //      case _ => hasReadAux(x)
    //    }
  }

  private def hasRead(xs: List[Tree]): Boolean = {
    xs.foldLeft(false)((z, x) => z || hasRead(x))
  }

  private def bindSymbols(app: Apply): List[Symbol] = {
    val args = app.args
    val argSyms = args.foldLeft(Nil: List[Symbol])((z, y) => {
      y match {
        case bind: Bind => z ++ bindSymbols(bind)
        case a @ Apply(_, _) => z ++ bindSymbols(a)
        case _ =>
          z
      }
    })
    argSyms
  }


  private def bindSymbols(b: Bind): List[Symbol] = {
    hasSymbol(b) match {
      case true =>
        val thisSym = b.symbol
        b.body match {
          case app @ Apply(_, _) =>
            thisSym :: bindSymbols(app)
          case _ => List(thisSym)
        }
      case false =>
        Nil
    }
  }



  /**
   * This function takes care of safely extracting a method whenever it sees a
   * call to read method of KaraVariable.
   *
   * Currently it extracts methods upon calls to read method per statement,
   * i.e. a method like the following will be split into two methods not three:
   *
   * {{{
   *  // Suppose that v and y is a self-adjusted variable
   *  def m(): Unit = {
   *  	v.write(v.read + "hello" + y.read)
   *  }
   * }}}
   */
  private def traverseAndExtract(definedVals: List[Symbol], 
        owner: DefDef, tree: Tree): 
              (Tree, List[DefDef]) = {
    tree match {
      case v: ValDef if hasSymbol(v) && !definedVals.contains(v.symbol) =>
        traverseAndExtract(v.symbol :: definedVals, owner, tree)
      case iff @ If(cond, thenp, elsep) =>
        val (newThen, extractedThenDefOpt) = thenp match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(definedVals, b, owner)
          case _ => (thenp, None)
        }

        val (newElse, extractedElseDefOpt) = elsep match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(definedVals, b, owner)
          case _ => (elsep, None)
        }

        val newIf = localTyper.typed { 
                treeCopy.If(iff, cond, newThen, newElse) }
        (newIf, extractedThenDefOpt.toList ++ extractedElseDefOpt.toList)
      case casedef @ CaseDef(pat, guard, body: Block) if hasRead(body) =>
        val newDefinedVals = pat match {
          case b @ Bind(_, body) if hasSymbol(b) =>
            definedVals ++ bindSymbols(b)
          case app @ Apply(_, _) =>
            definedVals ++ bindSymbols(app)
          case _ => definedVals
        }
        val (newBody, extractedBodyDefOpt) = body match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(newDefinedVals, b, owner)
          case _ => (body, None)
        }
        val newCaseDef = localTyper.typed {
          treeCopy.CaseDef(casedef, pat, guard, newBody)
        }
        (newCaseDef, extractedBodyDefOpt.toList)
      case fun @ Function(vparams, body: Block) if hasRead(body) =>
        val (newBody, extractedBodyDefOpt) = body match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(vparams.map(_.symbol) ++ definedVals, b, owner)
          case _ => (body, None)
        }
        val newFun = localTyper.typed {
          treeCopy.Function(fun, vparams, newBody)
        }
        (newFun, extractedBodyDefOpt.toList)
      case label @ LabelDef(name, params, rhs: Block) if hasRead(rhs) =>
        val (newBody, extractedBodyDefOpt) = rhs match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(params.map(_.symbol) ++ definedVals, b, owner)
          case _ => (rhs, None)
        }
        val newLabel = localTyper.typed {
          treeCopy.LabelDef(label, name, params, newBody)
        }
        (newLabel, extractedBodyDefOpt.toList)
      case matchDef @ Match(selector, cases) if hasRead(matchDef) =>
        val (newCases, defs) = cases.foldLeft((List.empty[CaseDef],
          List.empty[DefDef]))((z, y) => {
          val (tempCase, defs) = traverseAndExtract(definedVals, owner, y)
          (z._1 ++ List(tempCase.asInstanceOf[CaseDef]), defs ++ z._2)
        })
        val newMatch = localTyper.typed {
          treeCopy.Match(matchDef, selector, newCases)
        }
        (newMatch, defs)
      case tryDef @ Try(block, catches, finalizer) =>
        val (newBlock, blockDef) = extractBlock(definedVals, block,
          owner)
        val (newCatches, casesDefs) = catches.foldLeft((List.empty[CaseDef],
          List.empty[DefDef]))((z, y) => {
          val (tempCase, defs) = traverseAndExtract(definedVals, owner, y)
          (z._1 ++ List(tempCase.asInstanceOf[CaseDef]), defs ++ z._2)
        })
        // TODO you should add params added in body here too
        val (newFinalizer, finalizerDef) = 
              extractBlock(definedVals, finalizer,
          owner)
        val newTry = localTyper.typed {
          treeCopy.Try(tryDef, newBlock, newCatches, newFinalizer)
        }
        (newTry, blockDef.toList ++ casesDefs ++ finalizerDef)
      case valdef @ ValDef(mods, name, tpt, rhs: Block) if hasRead(rhs) =>
        val (newRhs, extractedBodyDefOpt) = rhs match {
          case b @ Block(stats, expr) if hasRead(stats) =>
            extractBlock(definedVals, b, owner)
          case _ => (rhs, None)
        }
        val newValDef = localTyper.typed {
          treeCopy.ValDef(valdef, mods, name, tpt, newRhs)
        }
        (newValDef, extractedBodyDefOpt.toList)
      case b @ Block(stats, expr) if hasRead(b) =>
        val (newStats, statDefs) = stats.foldLeft((List.empty[Tree],
          List.empty[DefDef]))((z, y) => {
          val (tempStat, defs) = traverseAndExtract(definedVals, owner, y)
          (z._1 ++ List(tempStat), defs ++ z._2)
        })
        val newDefinedVals = definedVals ++ identifyVariables(stats)
        val (newExpr, exprDefs) = expr match {
          case x: Match => traverseAndExtract(newDefinedVals, owner, x)
          case x: Try => traverseAndExtract(newDefinedVals, owner, x)
          case x: If => traverseAndExtract(newDefinedVals, owner, x)
          case x: Function => traverseAndExtract(newDefinedVals, owner, x)
          case x: CaseDef => traverseAndExtract(newDefinedVals, owner, x)
          case x: ValDef => traverseAndExtract(newDefinedVals, owner, x)
          case x: LabelDef => traverseAndExtract(newDefinedVals, owner, x)
          case _ => (expr, Nil)
        }
        val tempBlock = localTyper.typed {
          treeCopy.Block(b, newStats, newExpr)
        }.asInstanceOf[Block]
        val (newBlock, defs) = extractBlock(definedVals, tempBlock, owner)
        (newBlock, defs.toList ++ statDefs ++ exprDefs)
      case t => (t, Nil)
      // TODO
      // These currently need to be lambda lifted, then extracted
      // case ClassDef
      // case ModuleDef
      // case DefDef
    }
  }

  private def identifyVariables(ts: List[Tree]): List[Symbol] = {
    ts match {
      case Nil => Nil
      case (x: ValDef) :: xs => x.symbol :: identifyVariables(xs)
      case x :: xs => identifyVariables(xs)
    }
  }

  private def extractBlock(definedVals: List[Symbol],
    body: Tree, mtree: DefDef): (Tree, Option[DefDef]) = {
    body match {
      case Block(stats, expr) if hasRead(stats) =>
        val mthd = mtree.symbol
        val mthdOwner = mthd.enclClass
        val newMthdSymbol = mthdOwner.newMethodSymbol(
          unit.freshTermName(mthd.name.toString),
          mthdOwner.pos.focus, mthd.flags)
        val (stayed, extracted, updatedDefinedIdents) = 
            divideStats(definedVals, stats)
        val newBody = Block(extracted, expr)
        val usedSymbols = findUsedSymbols(updatedDefinedIdents, newBody)
        val (paramSyms, args) = 
            generateParamSymsAndArgs(newMthdSymbol, usedSymbols)
        val (newTparamSyms, newTargs) = generateTParamSymsAndTArgs(
              mtree.tparams, newMthdSymbol, paramSyms)
        val newTparams = generateTParams(newTparamSyms)

        val mthdTpe = generateTpe(newTparamSyms, paramSyms, mtree.tpt.tpe)
        newMthdSymbol.setInfoAndEnter(mthdTpe)
        val tpt = TypeTree(expr.tpe)

        val typedMthdTree = mkTypedDefDef(mthd, newMthdSymbol,
          newTparams, paramSyms, tpt, newBody)

        val newApply = 
          mkApply(mthd, newMthdSymbol, mtree.tpt.tpe, newTargs, args)

        val newBaseRhs = localTyper.typed { Block(stayed, newApply) }
        (newBaseRhs, Some(typedMthdTree))
      case _ => (body, None)
    }
  }

//  private def extractToMethod(cmp: TransformerComponent, definedVals: List[Symbol],
//    body: Tree, mtree: DefDef): (Tree, Option[DefDef]) = {
//    body match {
//      case Block(stats, expr) =>
//        val mthd = mtree.symbol
//        val mthdOwner = mthd.enclClass
//        val newMthdSymbol = mthdOwner.newMethodSymbol(
//          cmp.unit.freshTermName(mthd.name.toString),
//          mthdOwner.pos.focus, mthd.flags)
//        val newBody = Block(stats, expr)
//        val usedSymbols = findUsedSymbols(definedVals, newBody)
//        val (paramSyms, args) = generateParamSymsAndArgs(cmp, newMthdSymbol, usedSymbols)
//        val (newTparamSyms, newTargs) = generateTParamSymsAndTArgs(cmp, mtree.tparams, newMthdSymbol, paramSyms)
//        val newTparams = generateTParams(newTparamSyms)
//        val tpt = TypeTree(expr.tpe)
//        val mthdTpe = generateTpe(newTparamSyms, paramSyms, expr.tpe)
//        newMthdSymbol.setInfoAndEnter(mthdTpe)
//        
//
//        val typedMthdTree = mkTypedDefDef(cmp, mthd, newMthdSymbol,
//          newTparams, paramSyms, tpt, newBody)
//
//        val newApply = mkApply(cmp, mthd, newMthdSymbol, mtree.tpt.tpe, newTargs, args)
//
//        val newBaseRhs = cmp.localTyper.typed { newApply }
//        (newBaseRhs, Some(typedMthdTree))
//      case _ => (body, None)
//    }
//  }

  private def divideStats(definedVals: List[Symbol],
    stats: List[Tree]): (List[Tree], List[Tree], List[Symbol]) = {
    stats match {
      case (x: ValDef) :: xs if hasRead(x) =>
        (List(x), xs, x.symbol :: definedVals)
      case (x @ ValDef(_, _, _, _)) :: xs =>
        val (stayed, extracted, newVals) = 
            divideStats(x.symbol :: definedVals, xs)
        (x :: stayed, extracted, newVals)
      case x :: xs if hasRead(xs) =>
        (List(x), xs, definedVals)
      case x :: xs =>
        val (fst, snd, fv) = divideStats(definedVals, xs)
        (x :: fst, snd, fv)
      case Nil => (Nil, Nil, definedVals)
    }
  }

  //  private def traverseAndExtract(cmp: TransformerComponent, tree: Tree): Either[Tree, (Block, Block)] = {
  //    tree match {
  //      case Block(stmts, expr) if hasRead(stmts)=>
  //        val (fst, snd) = stmts.splitAt(
  //          stmts.indexWhere((x) => x.exists((y) => y match {
  //            case Select(qual, name) if (name == readName) => true
  //            case _ => false
  //          })) + 1)
  //        (fst, snd) match {
  //          case (Nil, rest) => Left(tree)
  //          case (xs, ys) =>
  //            val fst = Block(xs.dropRight(1), xs.last)
  //            val snd = Block(ys, expr)
  //            Right(fst, snd)
  //        }
  //
  //      case t => Left(t)
  //    }
  //  }

  private def findUsedSymbols(symbols: List[Symbol], 
            block: Block): List[Symbol] = {
    val usedSymbols = symbols.foldLeft(List.empty[Symbol])((z, y) => {
      block.exists((x) => {
        if (hasSymbol(x) && x.symbol == y) true else false
      }) match {
        case true => y :: z
        case false => z
      }
    })
    usedSymbols.reverse
  }
  //  private def findBaseOwnedIdents(base: DefDef, block: Tree): List[Tree] = {
  //    val mthdSymbol = base.symbol
  //    var ownedIdents = List.empty[Tree]
  //    block.foreach((x) =>
  //      if (x.isInstanceOf[Ident] && x.symbol.owner == mthdSymbol
  //        && !ownedIdents.exists((y) => x.symbol == y.symbol))
  //        ownedIdents = x :: ownedIdents)
  //    ownedIdents.foldLeft(List.empty[Tree])((z, y) =>
  //      base.exists((x) => x.isInstanceOf[ValDef] && x.symbol == y.symbol
  //        && !block.exists((k) => k.isInstanceOf[ValDef] && k.symbol == y.symbol)) match {
  //        case true => y :: z
  //        case false => z
  //      })
  //  }
  private def mkTypedDefDef(oldMethodSymbol: Symbol, newMethodSymbol: Symbol,
    newTparams: List[TypeDef], paramSyms: List[Symbol],
    tpt: Tree, rhs: Block): DefDef = {
    val newParams = generateParams(paramSyms)
    fixOwner(rhs, oldMethodSymbol, newMethodSymbol, paramSyms)

    val newMthdTree = DefDef(Modifiers(newMethodSymbol.flags),
      TermName(newMethodSymbol.name.toString), 
      newTparams, List(newParams), tpt,
      fixMutatingParams(rhs)).setSymbol(newMethodSymbol)

    localTyper.typed { newMthdTree }.asInstanceOf[DefDef]

  }

  private def mkApply(caller: Symbol, mthd: Symbol, mtpe: Type, 
          targs: List[Tree], args: List[Tree]): Apply = {
    val mthdOwner = mthd.owner
    val outerClass = mthd.enclClass
    val closureSelect = mthdOwner.isClass match {
      case true =>
        Select(This(outerClass), mthd)
      case false =>
        Ident(mthd)
    }
    val closure = {
      val closureSym = caller.newTermSymbol(
              TermName(tpnme.ANON_FUN_NAME.toString), 
              caller.pos.focus, SYNTHETIC)
      val closureParamSyms = args.map((x) => {
        val temp = closureSym.newValueParameter(
              TermName(x.symbol.name.toString), 
              closureSym.pos.focus, x.symbol.flags)
        temp.info = x.symbol.info
        temp
      })
      val closureParams = closureParamSyms.map((x) => {
        ValDef(x, EmptyTree)
      })
      val closureArgs = closureParamSyms.map((x) => Ident(x))
      val closureTpe = TypeRef(outerClass.info, closureSym, 
              closureParamSyms.map(_.info) ++ List(mtpe))

      closureSym.setInfo(closureTpe)
      val closureRhs = targs match {
        case Nil => Apply(closureSelect, closureArgs)
        case _ => Apply(TypeApply(closureSelect, targs), closureArgs)
      }
      Function(closureParams, closureRhs).setSymbol(closureSym)
    }
    val newArgs = Literal(Constant(mthd.fullName)) :: closure :: args

    val types = args.map((x) => TypeTree(x.tpe)) ++ List(TypeTree(mtpe))
    val lambda = types match {
      case Nil => Select(Ident(karaRuntime), runClosureName)
      case _ => TypeApply(Select(Ident(karaRuntime), runClosureName), types)
    }
    val apply = Apply(lambda, newArgs)

    val typedApply = localTyper.typed {
      apply
    }.asInstanceOf[Apply]
    typedApply
  }

  private def generateParams(paramSyms: List[Symbol]): List[ValDef] = {
    paramSyms.map((x) => ValDef(x, EmptyTree))
  }
  private def generateTpe(newTparamSyms: List[Symbol], 
          paramSyms: List[Symbol], ret: Type): Type = {
    newTparamSyms match {
      case Nil => MethodType(paramSyms, ret)
      case _ => PolyType(newTparamSyms, MethodType(paramSyms, ret))
    }
  }
  private def generateParamSymsAndArgs(newMethodSymbol: Symbol, 
          baseOwnedIdents: List[Symbol]): (List[Symbol], List[Tree]) = {
    val paramSyms = baseOwnedIdents.map(
      (x) => {
        val temp = 
          newMethodSymbol.newSyntheticValueParam(x.info, 
                      TermName(x.name.toString))
        temp.setAnnotations(x.annotations)
        temp.removeAnnotation(karaAnnotation)
        temp
      })

    val args = paramSyms.foldLeft(List.empty[Tree])((z, y) => {
      val id = Ident(baseOwnedIdents.find((x) => y.name == x.name).get)
      localTyper.typed(id) :: z
    }).reverse
    args.foreach(newMethodSymbol + "  " + _)
    paramSyms.foreach(newMethodSymbol + "  " + _)
    (paramSyms, args)
  }

  //  private def generateParamSymsAndArgs(cmp: TransformerComponent,
  //    newMethodSymbol: Symbol, baseOwnedIdents: List[Tree]): (List[Symbol], List[Tree]) = {
  //    val paramSyms = baseOwnedIdents.map(
  //      (x) => {
  //        newMethodSymbol.newSyntheticValueParam(x.symbol.info, x.symbol.name)
  //      })
  //
  //    val args = paramSyms.foldLeft(List.empty[Tree])((z, y) => {
  //      val id = Ident(baseOwnedIdents.find((x) => y.name == x.symbol.name).get.symbol)
  //      cmp.localTyper.typed(id) :: z
  //    }).reverse
  //    (paramSyms, args)
  //  }

  private def generateTParams(tparamSyms: List[Symbol]): List[TypeDef] = {
    tparamSyms.map((x) => TypeDef(x))
  }
  private def generateTParamSymsAndTArgs(originalTParams: List[Tree], 
      newMethodSymbol: Symbol, paramSyms: List[Symbol]): 
            (List[Symbol], List[Tree]) = {
    val commonTparams = 
        originalTParams.filter((t) => paramSyms.exists(t.symbol.tpe =:= _.info))
    val newTargs = commonTparams.map((x) => TypeTree(x.symbol.tpe))
    val tparamSyms = commonTparams.map(
      (x) => {
        val nsym = newMethodSymbol.newTypeParameter(
            unit.freshTypeName("K"), 
            newMethodSymbol.pos.focus, x.symbol.flags)

        nsym.info = x.symbol.info
        paramSyms.foreach((y) => {
          y.substInfo(List(x.symbol), List(nsym))
        })
        nsym
      })
    (tparamSyms, newTargs)
  }
  private def extractDefDef(mthd: DefDef): (DefDef, List[DefDef]) = {
    val vparamss = mthd.vparamss
    val rhs = mthd.rhs
    val tparams = mthd.tparams
    if (hasRead(mthd)) {
      /* 
       * Challenges? the extraction should take care of all the variables 
       * that is initialized earlier in this method and is used later after 
       * the read
       * It is easy to handle the cases of variables, but what happens if it 
       * defines inner methods and classes that are going to be used later?
       * 1- For defs, you can pass them as method parameters
       * 2- For classes, you can surround them with an inner def, that takes
       * the exact same variables of the classe's constructor. The def should
       * return a freshly created instance of the class. Then pass this def to 
       * the newly created method.
       * But neither of the previous two solutions work in practice, since when
       * the Kara runtime calls some kara-generated method, it fails to provide
       * these methods. 
       * -- Amanj
       **/

      //      val mthdSymbol = mthd.symbol
      //      val mthdOwner = mthd.symbol.owner
      //      traverseAndExtract(cmp, rhs) match {
      //        case Left(t) => (mthd, Nil)
      //        case Right((b1 @ Block(_, _), b2 @ Block(_, _))) =>
      //          val newMthdSymbol = mthdOwner.newMethodSymbol(
      //            cmp.unit.freshTermName(mthdSymbol.name.toString),
      //            mthdOwner.pos.focus, mthdSymbol.flags)
      //
      //          val baseOwnedIdents = findBaseOwnedIdents(mthd, b2)
      //          val (paramSyms, args) = generateParamSymsAndArgs(cmp, newMthdSymbol, baseOwnedIdents)
      //          val (newTparamSyms, newTargs) = generateTParamSymsAndTArgs(cmp, tparams, newMthdSymbol, paramSyms)
      //          val newTparams = generateTParams(newTparamSyms)
      //
      //          val mthdTpe = generateTpe(newTparamSyms, paramSyms, mthd.tpt.tpe)
      //          newMthdSymbol.setInfoAndEnter(mthdTpe)
      //          val typedMthdTree = mkTypedDefDef(cmp, mthdSymbol, newMthdSymbol,
      //            newTparams, paramSyms, mthd.tpt, b2)
      //
      //          val newApply = mkApply(cmp, mthdSymbol, newMthdSymbol, mthd.tpt.tpe, newTargs, args)
      //
      //          val newBaseRhs = cmp.localTyper.typed { Block(b1.stats ++ List(b1.expr), newApply) }
      //          

      val paramSyms = mthd.vparamss.flatten.map(_.symbol)
      val (newBaseRhs, typedMthdTrees) = 
            traverseAndExtract(paramSyms, mthd, rhs)

      val untypedBase = treeCopy.DefDef(mthd, mthd.mods, 
            mthd.name, mthd.tparams, mthd.vparamss, mthd.tpt, newBaseRhs)

      val baseMthd = localTyper.typed {
        untypedBase
      }.asInstanceOf[DefDef]
      //          (baseMthd, List(typedMthdTree))
      (baseMthd, typedMthdTrees)
      //      }
    } else
      (mthd, Nil)

  }

  private def fixMutatingParams(tree: Block): Block = {
    var aliases: Map[Symbol, Symbol] = Map.empty

    def substituteMutation(x: Tree): Tree = {
      x match {
        case t if (aliases.contains(t.symbol)) =>
          t.setSymbol(aliases(t.symbol))
        case Assign(lhs, rhs) if (isParam(lhs.symbol)) =>
          aliases.contains(lhs.symbol) match {
            case true =>
              Assign(Ident(aliases(lhs.symbol)), rhs)
            case false =>
              val newsym = lhs.symbol.owner.newVariable(
                unit.freshTermName(lhs.symbol.name + ""),
                lhs.symbol.owner.pos.focus, MUTABLE)
              newsym.info = lhs.symbol.info
              val newValDef = ValDef(newsym, rhs)
              aliases = aliases + (lhs.symbol -> newsym)
              newValDef
          }
        case t =>
          t.substituteSymbols(aliases.keys.toList, aliases.values.toList)
          t
      }
    }
    val newStats = tree.stats.foldLeft(List.empty[Tree])((z, y) => {
      z ++ List(substituteMutation(y))
    })

    val newExpr = substituteMutation(tree.expr)

    Block(newStats, newExpr)
  }

  private def doTransform(tree: Template): Template = {
    def traverseDefDef(mthds: List[DefDef]): List[DefDef] = {
      mthds match {
        case Nil => Nil
        case x :: xs =>
          val (fst, snd) = extractDefDef(x)
          fst :: traverseDefDef(snd ++ xs)
      }
    }

    val newBody = tree.body.foldLeft(List.empty[Tree])((z, y) =>
      y match {
        case x: DefDef =>
          val traversed = traverseDefDef(List(x))
          traversed ++ z
        case _ => y :: z
      })
    val tmpl = treeCopy.Template(tree, tree.parents, tree.self, newBody)
    tmpl
  }
  def transform(tree: Tree): Tree = {
    tree match {
      case clazz @ ClassDef(mods, name, tparams, impl) if hasRead(clazz) =>
        val newImpl = doTransform(impl)
        val newClazz = treeCopy.ClassDef(clazz, mods, name, tparams, newImpl)
        super.transform(localTyper.typed { newClazz })
      case module @ ModuleDef(mods, name, impl) if hasRead(module)=>
        val newImpl = doTransform(impl)
        val newModule = treeCopy.ModuleDef(module, mods, name, newImpl)
        super.transform(localTyper.typed { newModule })
      case _ => tree
    }
  }

}
