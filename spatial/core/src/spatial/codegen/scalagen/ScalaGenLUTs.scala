package spatial.codegen.scalagen

import spatial.SpatialExp
import org.virtualized.SourceContext

trait ScalaGenLUTs extends ScalaGenMemories {
  val IR: SpatialExp
  import IR._

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: LUTType[_] => src"Array[${tp.child}]"
    case _ => super.remap(tp)
  }
  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@LUTNew(dims,elems) => emitMem(lhs, src"""$lhs = Array[${op.mT}](${elems.map(quote).mkString(",")})""")
    case op@LUTLoad(rf,inds,en) =>
      val dims = dimsOf(rf).map(int32(_))
      open(src"val $lhs = {")
      oobApply(op.mT, rf, lhs, inds){ emit(src"if ($en) $rf.apply(${flattenAddress(dims,inds,None)}) else ${invalid(op.mT)}") }
      close("}")

    case _ => super.emitNode(lhs, rhs)
  }

}
