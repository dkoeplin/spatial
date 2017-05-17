package spatial.codegen.chiselgen

import argon.codegen.chiselgen.{ChiselCodegen}
import spatial.api.RegisterFileExp
import spatial.SpatialConfig
import spatial.SpatialExp

trait ChiselGenRegFile extends ChiselGenSRAM {
  val IR: SpatialExp
  import IR._

  private var nbufs: List[(Sym[SRAM[_]], Int)]  = List()

  override def quote(s: Exp[_]): String = {
    if (SpatialConfig.enableNaming) {
      s match {
        case lhs: Sym[_] =>
          lhs match {
            case Def(e: RegFileNew[_,_]) =>
              s"""x${lhs.id}_${nameOf(lhs).getOrElse("regfile")}"""
            case _ =>
              super.quote(s)
          }
        case _ =>
          super.quote(s)
      }
    } else {
      super.quote(s)
    }
  } 

  override protected def remap(tp: Type[_]): String = tp match {
    case tp: RegFileType[_] => src"Array[${tp.child}]"
    case _ => super.remap(tp)
  }

  override protected def emitNode(lhs: Sym[_], rhs: Op[_]): Unit = rhs match {
    case op@RegFileNew(dims) =>
      val width = bitWidth(lhs.tp.typeArguments.head)
      val par = writersOf(lhs).length
      duplicatesOf(lhs).zipWithIndex.foreach{ case (mem, i) => 
        val depth = mem match {
          case BankedMemory(dims, d, isAccum) => d
          case _ => 1
        }
        if (depth == 1) {
          emitGlobalModule(s"val ${quote(lhs)}_$i = Module(new templates.ShiftRegFile(${dims(0)}, ${dims(1)}, 1, ${par}/${dims(0)}, false, $width))")
          emitGlobalModule(s"${quote(lhs)}_$i.io.reset := reset")
        } else {
          nbufs = nbufs :+ (lhs.asInstanceOf[Sym[SRAM[_]]], i)
          emitGlobalModule(s"val ${quote(lhs)}_$i = Module(new templates.NBufShiftRegFile(${dims(0)}, ${dims(1)}, 1, $depth, ${par}/${dims(0)}, $width))")
          emitGlobalModule(s"${quote(lhs)}_$i.io.reset := reset")          
        }
      }
      
    case op@RegFileLoad(rf,inds,en) =>
      val dispatch = dispatchOf(lhs, rf).toList.head
      val port = portsOf(lhs, rf, dispatch).toList.head
      emit(src"""val ${lhs} = Wire(${newWire(lhs.tp)})""")
      emit(src"""${lhs}.raw := ${rf}_${dispatch}.readValue(${inds(0)}.raw, ${inds(1)}.raw, $port)""")

    case op@RegFileStore(rf,inds,data,en) =>
      duplicatesOf(rf).zipWithIndex.foreach{ case (mem, i) => 
        val port = portsOf(lhs, rf, i)
        val parent = writersOf(rf).find{_.node == lhs}.get.ctrlNode
        emit(s"""${quote(rf)}_${i}.connectWPort(${quote(data)}.raw, ${quote(inds(0))}.raw, ${quote(inds(1))}.raw, ${quote(en)} & (${quote(parent)}_datapath_en & ~${quote(parent)}_inhibitor).D(${symDelay(lhs)}), List(${port.toList.mkString(",")}))""")
      }

    case RegFileShiftIn(rf,inds,d,data,en)    => 
      duplicatesOf(rf).zipWithIndex.foreach{ case (mem, i) => 
        val port = portsOf(lhs, rf, i)
        val parent = writersOf(rf).find{_.node == lhs}.get.ctrlNode
        emit(s"""${quote(rf)}_${i}.connectShiftPort(${quote(data)}.raw, ${quote(inds(0))}.raw, ${quote(en)} & (${quote(parent)}_datapath_en & ~${quote(parent)}_inhibitor).D(${symDelay(lhs)}), List(${port.toList.mkString(",")}))""")
      }

    case ParRegFileShiftIn(rf,i,d,data,en) => 
      emit("ParRegFileShiftIn not implemented!")
      // (copied from ScalaGen) shiftIn(lhs, rf, i, d, data, isVec = true)

    case _ => super.emitNode(lhs, rhs)
  }


  override protected def emitFileFooter() {
    withStream(getStream("BufferControlCxns")) {
      nbufs.foreach{ case (mem, i) => 
        val readers = readersOf(mem)
        val writers = writersOf(mem)
        val readPorts = readers.filter{reader => dispatchOf(reader, mem).contains(i) }.groupBy{a => portsOf(a, mem, i) }
        val writePorts = writers.filter{writer => dispatchOf(writer, mem).contains(i) }.groupBy{a => portsOf(a, mem, i) }
        // Console.println(s"working on $mem $i $readers $readPorts $writers $writePorts")
        // Console.println(s"${readPorts.map{case (_, readers) => readers}}")
        // Console.println(s"innermost ${readPorts.map{case (_, readers) => readers.flatMap{a => topControllerOf(a,mem,i)}.head}.head.node}")
        // Console.println(s"middle ${parentOf(readPorts.map{case (_, readers) => readers.flatMap{a => topControllerOf(a,mem,i)}.head}.head.node).get}")
        // Console.println(s"outermost ${childrenOf(parentOf(readPorts.map{case (_, readers) => readers.flatMap{a => topControllerOf(a,mem,i)}.head}.head.node).get)}")
        val allSiblings = childrenOf(parentOf(readPorts.map{case (_, readers) => readers.flatMap{a => topControllerOf(a,mem,i)}.head}.head.node).get)
        val readSiblings = readPorts.map{case (_,r) => r.flatMap{ a => topControllerOf(a, mem, i)}}.filter{case l => l.length > 0}.map{case all => all.head.node}
        val writeSiblings = writePorts.map{case (_,r) => r.flatMap{ a => topControllerOf(a, mem, i)}}.filter{case l => l.length > 0}.map{case all => all.head.node}
        val writePortsNumbers = writeSiblings.map{ sw => allSiblings.indexOf(sw) }
        val readPortsNumbers = readSiblings.map{ sr => allSiblings.indexOf(sr) }
        val firstActivePort = math.min( readPortsNumbers.min, writePortsNumbers.min )
        val lastActivePort = math.max( readPortsNumbers.max, writePortsNumbers.max )
        val numStagesInbetween = lastActivePort - firstActivePort

        (0 to numStagesInbetween).foreach { port =>
          val ctrlId = port + firstActivePort
          val node = allSiblings(ctrlId)
          val rd = if (readPortsNumbers.toList.contains(ctrlId)) {"read"} else {
            // emit(src"""${mem}_${i}.readTieDown(${port})""")
            ""
          }
          val wr = if (writePortsNumbers.toList.contains(ctrlId)) {"write"} else {""}
          val empty = if (rd == "" & wr == "") "empty" else ""
          emit(src"""${mem}_${i}.connectStageCtrl(${quote(node)}_done, ${quote(node)}_en, List(${port})) /*$rd $wr $empty*/""")
        }


      }
    }

    super.emitFileFooter()
  }

}
