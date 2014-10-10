package com.googlecode.avro
package plugin

import ch.usi.inf.l3.lombrello.neve.NeveDSL._
// import nsc.typechecker
import scala.annotation.tailrec
import scala.reflect.internal.Flags._
import scala.collection.JavaConversions._

@phase("ctorretype") class CtorRetype {

  rightAfter("extender")

  plugin ScalaAvroPlugin

  def transform(tree: Tree) : Tree = {
    val newTree = tree match {
      // REMOVE synthethic hashCode and toString methods generated by typer
      // for an AvroRecord (we want to use the impl in SpecificRecordBase)
      case cd @ ClassDef(mods, name, tparams, impl) 
        if (cd.symbol.tpe.parents.contains(avroRecordTrait.tpe)) =>
        debug("removing synthetic methods for: " + currentClass.fullName)
        


        cd.removeMember((x) => x match { 
          case d @ DefDef(_, _, _, _, _, _) => 
            d.symbol.hasFlag(SYNTHETIC) &&  //SYNTHETICMETH
            (d.symbol.name == nme.toString_ || d.symbol.name == nme.hashCode_)
          case _ => false
        })
      case a @ Apply(sel @ Select(sup @ Super(qual, name), name1), args) => 
        if (currentClass.tpe.parents.contains(avroRecordTrait.tpe)) {
          debug("retyping ctor reference for: " + currentClass.fullName)
          localTyper typed {
            Apply(Select(Super(qual, name) setPos sup.pos, name1) setPos sel.pos, transformTrees(args)) setPos tree.pos
          }
        } else {
          debug("skipping super reference: " + currentClass.fullName)
          tree
        }
      case _ => tree
    }
    super.transform(newTree)
  }    
}
