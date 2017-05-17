package spatial.transform

import argon.transform.ForwardTransformer
import spatial._
import spatial.api.ControllerApi
import org.virtualized.SourceContext

trait UnrollingTransformer extends ForwardTransformer { self =>
  val IR: SpatialExp with ControllerApi
  import IR._

  override val name = "Unrolling Transformer"
  var inHwScope: Boolean = false

  def strMeta(e: Exp[_]): Unit = metadata.get(e).foreach{m => logs(c" - ${m._1}: ${m._2}") }



  /**
    * Clone functions - used to add extra rules (primarily for metadata) during unrolling
    * Applied directly after mirroring
    */
  var cloneFuncs: List[Exp[_] => Unit] = Nil
  def duringClone[T](func: Exp[_] => Unit)(blk: => T)(implicit ctx: SrcCtx): T = {
    val prevCloneFuncs = cloneFuncs
    cloneFuncs = cloneFuncs :+ func   // Innermost is executed last

    dbgs(s"[Compiler] Starting clone function at $ctx [${cloneFuncs.size}]")

    val result = blk
    cloneFuncs = prevCloneFuncs

    dbgs(s"[Compiler] End of clone function at $ctx")

    result
  }
  def inReduction[T](isInner: Boolean)(blk: => T): T = {
    duringClone{e => if (SpatialConfig.enablePIR && !isInner) reduceType(e) = None }{ blk }
  }
  def inCycle[T](reduceTp: Option[ReduceFunction])(blk: => T): T = {
    duringClone{e => if (SpatialConfig.enablePIR) reduceType(e) = reduceTp }{ blk }
  }

  /**
    * Valid bits - tracks all valid bits associated with the current scope to handle edge cases
    * e.g. cases where parallelization is not an even divider of counter max
    */
  var validBits: Seq[Exp[Bool]] = Nil
  def withValids[T](valids: Seq[Exp[Bool]])(blk: => T): T = {
    val prevValids = validBits
    validBits = valids
    val result = blk
    validBits = prevValids
    result
  }

  // Single global valid - should only be used in inner pipes - creates AND tree
  def globalValid = {
    if (validBits.isEmpty) bool(true)
    else reduceTree(validBits){(a,b) => bool_and(a,b) }
  }

  // Sequence of valid bits associated with current unrolling scope
  def globalValids = if (validBits.nonEmpty) validBits else Seq(bool(true))


  /**
    * Unroll numbers - gives the unroll index of each pre-unrolled (prior to transformer) index
    * Used to determine which duplicate a particular memory access should be associated with
    */
  var unrollNum = Map[Exp[Index], Int]()
  def withUnrollNums[A](ind: Seq[(Exp[Index], Int)])(blk: => A) = {
    val prevUnroll = unrollNum
    unrollNum ++= ind
    val result = blk
    unrollNum = prevUnroll
    result
  }

  /**
    * "Pachinko" rules:
    *
    * Given a memory access which depends on indices i1 ... iN,
    * and some parallelization factors P1 ... PN associated with each index,
    *
    *
    * When a new memory access is unrolled:
    *   1. Check other unrolled accesses which have the same original symbol
    *   2.a. Look up all loop iterators used to compute the indices for this access
    *   2.b. Look up the unroll number for each of these iterators (-1 for random accesses)
    *   3. The duplicate of the access is the number of accesses which have already been unrolled with the same numbers
    *   4. Update list of accesses to include current's numbers
    */
  var pachinko = Map[(Exp[_],Exp[_],Int), Seq[Seq[Int]]]()

  def registerAccess(original: Exp[_], unrolled: Exp[_] /*, pars: Option[Seq[Int]]*/): Unit = {
    val reads = unrolled match { case LocalReader(rds) => rds.map(_._1); case _ => Nil }
    val writes = unrolled match { case LocalWriter(wrts) => wrts.map(_._1); case _ => Nil }
    //val accesses = reads ++ writes

    val unrollInts: Seq[Int] = accessPatternOf.get(original).map{patterns =>
      patterns.map(_.index.flatMap{i => unrollNum.get(i)}.getOrElse(-1) )
    }.getOrElse(Seq(-1))

    // Total number of address channels needed
    val channels = unrolled match {
      case Def(op: EnabledOp[_]) => op.enables.length
      case _ => unrolled.tp match {
        case tp: VectorType[_] => tp.width
        case _ => 1
      }
    }

    // For each memory this access reads, set the new dispatch value
    reads.foreach{mem =>
      dbgs(u"Registering read of $mem: " + c"$original -> $unrolled")
      dbgs(c"  Channels: $channels")
      dbgs(c"  ${str(original)}")
      dbgs(c"  ${str(unrolled)}")
      dbgs(c"  Unroll numbers: $unrollInts")

      val origDispatches = dispatchOf(unrolled, mem)
      if (origDispatches.size != 1) {
        error(c"Readers should have exactly one dispatch, $original -> $unrolled had ${origDispatches.size}")
        sys.exit()
      }
      val dispatches = origDispatches.flatMap{orig =>
        dbgs(c"  Dispatch #$orig: ")
        dbgs(c"    Previous unroll numbers: ")
        val others = pachinko.getOrElse( (original,mem,orig), Nil)

        val banking = duplicatesOf(mem).apply(orig) match {
          case banked: BankedMemory => banked.dims.map(_.banks)
          case diagonal: DiagonalMemory => Seq(diagonal.banks) ++ List.fill(diagonal.nDims - 1)(1)
        }

        // Address channels taken care of by banking
        val bankedChannels = accessPatternOf.get(original).map{ patterns =>
          val iters = patterns.map(_.index)
          iters.distinct.map{
            case x@Some(i) =>
              val requiredBanking = parFactorOf(i) match {case Exact(p) => p.toInt }
              val actualBanking = banking(iters.indexOf(x))
              Math.min(requiredBanking, actualBanking) // actual may be higher than required, or vice versa
            case None => 1
          }
        }.getOrElse(Seq(1))

        val banks = bankedChannels.product
        val duplicates = (channels + banks - 1) / banks // ceiling(channels/banks)

        dbgs(c"    Bankings: $banking")
        dbgs(c"    Banked Channels: $bankedChannels ($banks)")
        dbgs(c"    Duplicates: $duplicates")

        others.foreach{other => dbgs(c"      $other") }

        val dispatchStart = orig + duplicates * others.count{p => p == unrollInts }
        pachinko += (original,mem,orig) -> (unrollInts +: others)

        val dispatches = List.tabulate(duplicates){i => dispatchStart + i}.toSet

        dbgs(c"    Setting new dispatch values: $dispatches")

        //dispatchOf(unrolled, mem) = Set(dispatch)
        dispatches.foreach{d => portsOf(unrolled, mem, d) = portsOf(unrolled, mem, orig) }
        dispatches
      }
      dispatchOf(unrolled, mem) = dispatches
    }
  }


  /**
    * Helper class for unrolling
    * Tracks multiple substitution contexts in 'contexts' array
    **/
  case class Unroller(cchain: Exp[CounterChain], inds: Seq[Bound[Index]], isInnerLoop: Boolean) {
    val fs = countersOf(cchain).map(isForever)

    // Don't unroll inner loops for CGRA generation
    val Ps = if (isInnerLoop && SpatialConfig.enablePIR) inds.map{_ => 1} else parFactorsOf(cchain).map{case Exact(c) => c.toInt }

    val P = Ps.product
    val N = Ps.length
    val prods = List.tabulate(N){i => Ps.slice(i+1,N).product }
    val indices: Seq[Seq[Bound[Index]]]    = Ps.map{p => List.fill(p){ fresh[Index] }}
    val indexValids: Seq[Seq[Bound[Bool]]] = Ps.map{p => List.fill(p){ fresh[Bool] }}

    // Valid bits corresponding to each lane
    lazy val valids: List[Seq[Exp[Bool]]]  = List.tabulate(P){p =>
      val laneIdxValids = indexValids.zip(parAddr(p)).map{case (vec,i) => vec(i)}
      laneIdxValids ++ validBits
    }

    def size = P

    def parAddr(p: Int) = List.tabulate(N){d => (p / prods(d)) % Ps(d) }

    // Substitution for each duplication "lane"
    val contexts = Array.tabulate(P){p =>
      val inds2 = indices.zip(parAddr(p)).map{case (vec, i) => vec(i) }
      Map.empty[Exp[Any],Exp[Any]] ++ inds.zip(inds2)
    }

    def inLane[A](i: Int)(block: => A): A = {
      val save = subst
      val addr = parAddr(i)
      withUnrollNums(inds.zip(addr)) {
        withSubstRules(contexts(i)) {
          withValids(valids(i)) {
            val result = block
            // Retain only the substitutions added within this scope
            contexts(i) ++= subst.filterNot(save contains _._1)
            result
          }
        }
      }
    }

    def map[A](block: Int => A): List[A] = List.tabulate(P){p => inLane(p){ block(p) } }

    def foreach(block: Int => Unit) { map(block) }

    // --- Each unrolling rule should do at least one of three things:
    // 1. Split a given vector as the substitution for the single original symbol
    def duplicate[A](s: Sym[A], d: Op[A]): List[Exp[_]] = map{_ =>
      val s2 = cloneOp(s, d)
      register(s -> s2)
      s2
    }
    // 2. Make later stages depend on the given substitution across all lanes
    // NOTE: This assumes that the node has no meaningful return value (i.e. all are Pipeline or Unit)
    // Bad things can happen here if you're not careful!
    def split[T:Type](orig: Sym[T], vec: Exp[Vector[_]])(implicit ctx: SrcCtx): List[Exp[T]] = map{p =>
      val element = vector_apply[T](vec.asInstanceOf[Exp[Vector[T]]], p)(typ[T],ctx)
      register(orig -> element)
      element
    }
    // 3. Create an unrolled mapping of symbol (orig -> unrolled) for each lane
    def unify[T](orig: Exp[T], unrolled: Exp[T]): List[Exp[T]] = {
      foreach{p => register(orig -> unrolled) }
      List(unrolled)
    }

    // Same symbol for all lanes
    def isCommon(e: Exp[_]) = contexts.map{p => f(e)}.forall{e2 => e2 == f(e)}
  }

  override def transform[A:Type](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Exp[A] = (rhs match {
    case e:Hwblock =>
      inHwScope = true
      val lhs2 = super.transform(lhs,rhs)
      inHwScope = false
      lhs2
    case e:OpForeach        => unrollForeachNode(lhs, e)
    case e:OpReduce[_]      => unrollReduceNode(lhs, e)
    case e:OpMemReduce[_,_] => unrollMemReduceNode(lhs, e)
    case _ => super.transform(lhs, rhs)
  }).asInstanceOf[Exp[A]]

  val writeParallelizer: PartialFunction[(Unroller, Def, SrcCtx),Exp[Void]] = {
    case (lanes, e@RegFileStore(reg,inds,data,en), ctx) =>
      val addrs = lanes.map{p => inds.map(f(_)) }
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => f(en) }
      par_regfile_store(f(reg),addrs,datas,ens)(e.mT,e.bT,ctx)

    case (lanes, e@LineBufferEnq(lb,data,en), ctx) =>
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => bool_and(f(en), globalValid) }
      par_linebuffer_enq(f(lb), datas, ens)(e.mT,e.bT,ctx)

    case (lanes, e@FIFOEnq(fifo, data, en), ctx) =>
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => bool_and( f(en), globalValid) }
      par_fifo_enq(f(fifo), datas, ens)(e.mT,e.bT,ctx)

    case (lanes, e@FILOPush(filo, data, en), ctx) =>
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => bool_and( f(en), globalValid) }
      par_filo_push(f(filo), datas, ens)(e.mT,e.bT,ctx)

    case (lanes, e@StreamWrite(stream, data, en), ctx) =>
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => bool_and( f(en), globalValid) }
      par_stream_write(f(stream), datas, ens)(e.mT,e.bT,ctx)

    // TODO: Assuming dims and ofs are not needed for now
    case (lanes, e@SRAMStore(sram,dims,inds,ofs,data,en), ctx) =>
      val addrs = lanes.map{p => inds.map(f(_)) }
      val datas = lanes.map{p => f(data) }
      val ens   = lanes.map{p => bool_and(f(en), globalValid) }
      par_sram_store(f(sram), addrs, datas, ens)(e.mT,e.bT,ctx)
  }

  val readParallelizer: PartialFunction[(Unroller, Def, SrcCtx),Exp[Vector[_]]] = {
    case (lanes, e@RegFileLoad(reg,inds,en), ctx) =>
      val addrs = lanes.map{p => inds.map(f(_)) }
      val ens   = lanes.map{p => f(en) }
      par_regfile_load(f(reg), addrs, ens)(mtyp(e.mT), mbits(e.bT), ctx)

    case (lanes, e@LineBufferLoad(lb,row,col,en), ctx) =>
      val rows = lanes.map{p => f(row) }
      val cols = lanes.map{p => f(col) }
      val ens  = lanes.map{p => f(en) }
      par_linebuffer_load(f(lb), rows, cols, ens)(mtyp(e.mT),mbits(e.bT),ctx)

    case (lanes, e@FIFODeq(fifo, en), ctx) =>
      val enables = lanes.map{p => bool_and(f(en), globalValid) }
      par_fifo_deq(f(fifo), enables)(mtyp(e.mT),mbits(e.bT),ctx)

    case (lanes, e@FILOPop(filo, en), ctx) =>
      val enables = lanes.map{p => bool_and(f(en), globalValid) }
      par_filo_pop(f(filo), enables)(mtyp(e.mT),mbits(e.bT),ctx)

    case (lanes, e@StreamRead(stream, en), ctx) =>
      val enables = lanes.map{p => bool_and(f(en), globalValid) }
      par_stream_read(f(stream), enables)(mtyp(e.mT),mbits(e.bT),ctx)

    // TODO: Assuming dims and ofs are not needed for now
    case (lanes, e@SRAMLoad(sram,dims,inds,ofs,en), ctx) =>
      val addrs = lanes.map{p => inds.map(f(_)) }
      val ens   = lanes.map{p => bool_and(f(en), globalValid) }
      par_sram_load(f(sram), addrs, ens)(mtyp(e.mT),mbits(e.bT),ctx)
  }

  /**
    * Create duplicates of the given node or special case, vectorized version
    * NOTE: Only can be used within reify scope
    * TODO: Whitelist loop invariant nodes
    **/
  def shouldUnrollAccess(lhs: Sym[_], rhs: Op[_], lanes: Unroller): Boolean = rhs match {
    case LocalReader(reads) if reads.forall{r => lanes.isCommon(r.mem) } => rhs match {
      case _:SRAMLoad[_] => !isLoopInvariant(lhs)
      case _ => true
    }

    case LocalWriter(writes) if writes.forall{r => lanes.isCommon(r.mem) } => rhs match {
      case _:SRAMStore[_] => !isLoopInvariant(lhs)
      case _ => true
    }
    case _ => false
  }
  def shouldUnifyAccess(lhs: Sym[_], rhs: Op[_], lanes: Unroller): Boolean = rhs match {
    case LocalReader(reads)  if isLoopInvariant(lhs) && reads.forall{r => lanes.isCommon(r.mem) } => rhs match {
      case _:SRAMLoad[_] => true
      case _ => false
    }
    case LocalWriter(writes) if isLoopInvariant(lhs) && writes.forall{r => lanes.isCommon(r.mem) } => rhs match {
      case _:SRAMStore[_] => true
      case _ => false
    }
    case _ => false
  }

  def unroll[T](lhs: Sym[T], rhs: Op[T], lanes: Unroller)(implicit ctx: SrcCtx): List[Exp[_]] = rhs match {
    // Account for the edge ca e with FIFO writing
    case LocalReader(_) if shouldUnrollAccess(lhs, rhs, lanes) && readParallelizer.isDefinedAt((lanes,rhs,ctx)) =>
      val lhs2 = readParallelizer.apply((lanes,rhs,ctx))
      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }
      registerAccess(lhs, lhs2)
      logs(s"Unrolling $lhs = $rhs"); strMeta(lhs)
      logs(s"Created ${str(lhs2)}"); strMeta(lhs2)
      lanes.split(lhs, lhs2)(mtyp(lhs.tp),ctx)

    case LocalWriter(_) if shouldUnrollAccess(lhs, rhs, lanes) && writeParallelizer.isDefinedAt((lanes,rhs,ctx)) =>
      val lhs2 = writeParallelizer.apply((lanes,rhs,ctx))
      transferMetadata(lhs, lhs2)
      cloneFuncs.foreach{func => func(lhs2) }
      registerAccess(lhs, lhs2)
      logs(s"Unrolling $lhs = $rhs"); strMeta(lhs)
      logs(s"Created ${str(lhs2)}"); strMeta(lhs2)
      lanes.unify(lhs, lhs2)

    case e: OpForeach        => unrollControllers(lhs,rhs,lanes){ unrollForeachNode(lhs, e) }
    case e: OpReduce[_]      => unrollControllers(lhs,rhs,lanes){ unrollReduceNode(lhs, e) }
    case e: OpMemReduce[_,_] => unrollControllers(lhs,rhs,lanes){ unrollMemReduceNode(lhs, e) }
    case _ if isControlNode(lhs) => unrollControllers(lhs,rhs,lanes){ cloneOp(lhs, rhs) }

    case e: RegNew[_] =>
      logs(s"Duplicating $lhs = $rhs")
      val dups = lanes.duplicate(lhs, rhs)
      logs(s"  Created registers: ")
      lanes.foreach{p => dbgs(s"  $p: $lhs -> ${f(lhs)}") }
      dups

    // For inner loop invariant ops, no need to copy the operation - can just do once and broadcast to all lanes
    case _:EnabledOp[_] if shouldUnifyAccess(lhs, rhs, lanes) =>
      logs(s"Unifying $lhs = $rhs (loop invariant)")
      val lhs2 = lanes.inLane(0){ cloneOp(lhs, rhs) } // Doesn't matter which lane, as long as it's in one of them
      lanes.unify(lhs, lhs2)

    case _ =>
      logs(s"Duplicating $lhs = $rhs")
      val dups = lanes.duplicate(lhs, rhs)
      dups
  }


  def unrollControllers[T](lhs: Sym[T], rhs: Op[T], lanes: Unroller)(unroll: => Exp[_]) = {
    logs(s"Unrolling controller:")
    logs(s"$lhs = $rhs")
    if (lanes.size > 1) {
      val lhs2 = op_parallel_pipe(globalValids, {
        lanes.foreach{p =>
          logs(s"$lhs duplicate ${p+1}/${lanes.size}")
          unroll
        }
        void
      })
      lanes.unify(lhs, lhs2)
    }
    else {
      logs(s"$lhs duplicate 1/1")
      val first = lanes.inLane(0){ unroll }
      lanes.unify(lhs, first)
    }
  }


  /**
    * Unrolls purely independent loop iterations
    * NOTE: The func block should already have been mirrored to update dependencies prior to unrolling
    */
  def unrollMap[T:Type](func: Block[T], lanes: Unroller)(implicit ctx: SrcCtx): List[Exp[T]] = {
    val origResult = func.result

    tab += 1
    mangleBlock(func, {stms =>
      stms.foreach{case TP(lhs,rhs) => unroll(lhs, rhs, lanes)(lhs.ctx) }
    })
    tab -= 1

    // Get the list of duplicates for the original result of this block
    lanes.map{p => f(origResult) }
  }


  def unrollForeach (
    lhs:    Exp[_],
    cchain: Exp[CounterChain],
    func:   Block[Void],
    iters:  Seq[Bound[Index]]
  )(implicit ctx: SrcCtx) = {
    logs(s"Unrolling foreach $lhs")

    val lanes = Unroller(cchain, iters, isInnerControl(lhs))
    val is = lanes.indices
    val vs = lanes.indexValids

    val blk = stageColdBlock { unrollMap(func, lanes); void }


    val effects = blk.summary
    val lhs2 = stageEffectful(UnrolledForeach(globalValids, cchain, blk, is, vs), effects.star)(ctx)
    transferMetadata(lhs, lhs2)

    logs(s"Created foreach ${str(lhs2)}")
    lhs2
  }
  def unrollForeachNode(lhs: Sym[_], rhs: OpForeach)(implicit ctx: SrcCtx) = {
    val OpForeach(cchain, func, iters) = rhs
    unrollForeach(lhs, f(cchain), func, iters)
  }


  def unrollReduceTree[T:Type:Bits](
    inputs: Seq[Exp[T]],
    valids: Seq[Exp[Bool]],
    ident:   Option[Exp[T]],
    reduce: (Exp[T], Exp[T]) => Exp[T]
  )(implicit ctx: SrcCtx): Exp[T] = ident match {
    case Some(z) =>
      val validInputs = inputs.zip(valids).map{case (in,v) => math_mux(v, in, z) }
      reduceTree(validInputs){(x: Exp[T], y: Exp[T]) => reduce(x,y) }

    case None =>
      // ASSUMPTION: If any values are invalid, they are at the end of the list (corresponding to highest index values)
      // TODO: This may be incorrect if we parallelize by more than the innermost iterator
      val inputsWithValid = inputs.zip(valids)
      reduceTree(inputsWithValid){(x: (Exp[T], Exp[Bool]), y: (Exp[T],Exp[Bool])) =>
        val res = reduce(x._1, y._1)
        (math_mux(y._2, res, x._1), bool_or(x._2, y._2)) // res is valid if x or y is valid
      }._1
  }


  def unrollReduceAccumulate[T:Type:Bits](
    inputs: Seq[Exp[T]],          // Symbols to be reduced
    valids: Seq[Exp[Bool]],       // Data valid bits corresponding to inputs
    ident:  Option[Exp[T]],       // Optional identity value
    fold:   Option[Exp[T]],       // Optional fold value
    rFunc:  Block[T],             // Reduction function
    load:   Block[T],             // Load function from accumulator
    store:  Block[Void],          // Store function to accumulator
    rV:     (Bound[T], Bound[T]), // Bound symbols used to reify rFunc
    iters:  Seq[Bound[Index]],    // Iterators for entire reduction (used to determine when to reset)
    start:  Seq[Exp[Index]],      // Start for each iterator
    isInner: Boolean
  )(implicit ctx: SrcCtx) = {
    def reduce(x: Exp[T], y: Exp[T]) = withSubstScope(rV._1 -> x, rV._2 -> y){ inlineBlock(rFunc) }

    val treeResult = inReduction(isInner){ unrollReduceTree[T](inputs, valids, ident, reduce) }
    val redType = reduceType(rFunc.result)

    val result = inReduction(isInner){
      val accValue = inlineBlock(load)
      val isFirst = reduceTree(iters.zip(start).map{case (i,st) => fix_eql(i, st) }){(x,y) => bool_and(x,y) }

      isReduceStarter(accValue) = true

      if (SpatialConfig.enablePIR) {
        inCycle(redType){ reduce(treeResult, accValue) }
      }
      else fold match {
        // FOLD: On first iteration, use init value rather than zero
        case Some(init) =>
          val accumOrFirst = math_mux(isFirst, init, accValue)
          reduceType(accumOrFirst) = redType
          reduce(treeResult, accumOrFirst)

        // REDUCE: On first iteration, store result of tree, do not include value from accum
        // TODO: Could also have third case where we use ident instead of loaded value. Is one better?
        case None =>
          val res2 = reduce(treeResult, accValue)
          val mux = math_mux(isFirst, treeResult, res2)
          reduceType(mux) = redType
          mux
      }
    }

    isReduceResult(result) = true

    inReduction(isInner){ withSubstScope(rFunc.result -> result){ inlineBlock(store) } }
  }

  def unrollReduce[T](
    lhs:    Exp[_],               // Original pipe symbol
    cchain: Exp[CounterChain],    // Counterchain
    accum:  Exp[Reg[T]],          // Accumulator
    ident:  Option[Exp[T]],       // Optional identity value for reduction
    fold:   Option[Exp[T]],       // Optional value to fold with reduction
    load:   Block[T],             // Load function for accumulator
    store:  Block[Void],          // Store function for accumulator
    func:   Block[T],             // Map function
    rFunc:  Block[T],             // Reduce function
    rV:     (Bound[T],Bound[T]),  // Bound symbols used to reify rFunc
    iters:  Seq[Bound[Index]]     // Bound iterators for map loop
  )(implicit mT: Type[T], bT: Bits[T], ctx: SrcCtx) = {
    logs(s"Unrolling pipe-fold $lhs")
    val lanes = Unroller(cchain, iters, isInnerControl(lhs))
    val inds2 = lanes.indices
    val vs = lanes.indexValids
    val mC = typ[Reg[T]]
    val start = counterStarts(cchain).map(_.getOrElse(int32(0)))

    val blk = stageColdLambda(f(accum)) {
      logs("Unrolling map")
      val values = unrollMap(func, lanes)(mT,ctx)
      val valids = () => lanes.valids.map{vs => reduceTree(vs){(a,b) => bool_and(a,b) } }

      if (isOuterControl(lhs)) {
        logs("Unrolling unit pipe reduce")
        Pipe { Void(unrollReduceAccumulate[T](values, valids(), ident, fold, rFunc, load, store, rV, inds2.map(_.head), start, isInner = false)) }
      }
      else {
        logs("Unrolling inner reduce")
        unrollReduceAccumulate[T](values, valids(), ident, fold, rFunc, load, store, rV, inds2.map(_.head), start, isInner = true)
      }
      void
    }
    val rV2 = (fresh[T],fresh[T])
    val rFunc2 = withSubstScope(rV._1 -> rV2._1, rV._2 -> rV2._2){ transformBlock(rFunc) }

    val effects = blk.summary
    val lhs2 = stageEffectful(UnrolledReduce(globalValids, cchain, accum, blk, rFunc2, inds2, vs, rV2)(mT,mC), effects.star)(ctx)
    transferMetadata(lhs, lhs2)
    logs(s"Created reduce ${str(lhs2)}")
    lhs2
  }
  def unrollReduceNode[T](lhs: Sym[_], rhs: OpReduce[T])(implicit ctx: SrcCtx) = {
    val OpReduce(cchain,accum,map,load,reduce,store,zero,fold,rV,iters) = rhs
    unrollReduce[T](lhs, f(cchain), f(accum), zero, fold, load, store, map, reduce, rV, iters)(rhs.mT, rhs.bT, ctx)
  }



  def unrollMemReduce[T,C[T]](
    lhs:   Exp[_],                  // Original pipe symbol
    cchainMap: Exp[CounterChain],   // Map counterchain
    cchainRed: Exp[CounterChain],   // Reduction counterchain
    accum:    Exp[C[T]],            // Accumulator (external)
    ident:    Option[Exp[T]],       // Optional identity value for reduction
    fold:     Boolean,              // Optional value to fold with reduction
    func:     Block[C[T]],          // Map function
    loadRes:  Block[T],             // Load function for intermediate values
    loadAcc:  Block[T],             // Load function for accumulator
    rFunc:    Block[T],             // Reduction function
    storeAcc: Block[Void],          // Store function for accumulator
    rV:       (Bound[T],Bound[T]),  // Bound symbol used to reify rFunc
    itersMap: Seq[Bound[Index]],    // Bound iterators for map loop
    itersRed: Seq[Bound[Index]]     // Bound iterators for reduce loop
  )(implicit mT: Type[T], bT: Bits[T], mC: Type[C[T]], ctx: SrcCtx) = {
    logs(s"Unrolling accum-fold $lhs")

    def reduce(x: Exp[T], y: Exp[T]) = withSubstScope(rV._1 -> x, rV._2 -> y){ inlineBlock(rFunc)(mT) }

    val mapLanes = Unroller(cchainMap, itersMap, isInnerLoop = false)
    val isMap2 = mapLanes.indices
    val mvs = mapLanes.indexValids
    val partial = func.result
    val start = counterStarts(cchainMap).map(_.getOrElse(int32(0)))
    val redType = reduceType(rFunc.result)

    val blk = stageColdLambda(f(accum)) {
      logs(s"[Accum-fold $lhs] Unrolling map")
      val mems = unrollMap(func, mapLanes)
      val mvalids = () => mapLanes.valids.map{vs => reduceTree(vs){(a,b) => bool_and(a,b)} }

      if (isUnitCounterChain(cchainRed)) {
        logs(s"[Accum-fold $lhs] Unrolling unit pipe reduction")
        op_unit_pipe(globalValids, {
          val values = inReduction(false){ mems.map{mem => withSubstScope(partial -> mem){ inlineBlock(loadRes)(mT) }} }
          val foldValue = if (fold) { Some( inlineBlock(loadAcc)(mT) ) } else None
          inReduction(false){ unrollReduceAccumulate[T](values, mvalids(), ident, foldValue, rFunc, loadAcc, storeAcc, rV, isMap2.map(_.head), start, isInner = false) }
          void
        })
      }
      else {
        logs(s"[Accum-fold $lhs] Unrolling pipe-reduce reduction")
        tab += 1

        val reduceLanes = Unroller(cchainRed, itersRed, true)
        val isRed2 = reduceLanes.indices
        val rvs = reduceLanes.indexValids
        reduceLanes.foreach{p =>
          logs(s"Lane #$p")
          itersRed.foreach{i => logs(s"  $i -> ${f(i)}") }
        }

        val rBlk = stageColdBlock {
          logs(c"[Accum-fold $lhs] Unrolling map loads")
          logs(c"  memories: $mems")

          val values: Seq[Seq[Exp[T]]] = inReduction(false){
            mems.map{mem =>
              withSubstScope(partial -> mem) {
                unrollMap(loadRes, reduceLanes)(mT,ctx)
              }
            }
          }

          logs(s"[Accum-fold $lhs] Unrolling accum loads")
          reduceLanes.foreach{p =>
            logs(s"Lane #$p")
            itersRed.foreach{i => logs(s"  $i -> ${f(i)}") }
          }

          val accValues = inReduction(false){ unrollMap(loadAcc, reduceLanes)(mT,ctx) }

          logs(s"[Accum-fold $lhs] Unrolling reduction trees and cycles")
          reduceLanes.foreach{p =>
            val laneValid = reduceTree(reduceLanes.valids(p)){(a,b) => bool_and(a,b)}

            logs(s"Lane #$p:")
            tab += 1
            val inputs = values.map(_.apply(p)) // The pth value of each vector load
            val valids = mvalids().map{mvalid => bool_and(mvalid, laneValid) }

            logs("Valids:")
            valids.foreach{s => logs(s"  ${str(s)}")}

            logs("Inputs:")
            inputs.foreach{s => logs(s"  ${str(s)}") }

            val accValue = accValues(p)

            val result = inReduction(true){
              val treeResult = unrollReduceTree(inputs, valids, ident, reduce)
              val isFirst = reduceTree(isMap2.map(_.head).zip(start).map{case (i,st) => fix_eql(i, st) }){(x,y) => bool_and(x,y) }

              isReduceStarter(accValue) = true

              if (SpatialConfig.enablePIR) {
                inCycle(redType){ reduce(treeResult, accValue) }
              }
              else if (fold) {
                // FOLD: On first iteration, use value of accumulator value rather than zero
                //val accumOrFirst = math_mux(isFirst, init, accValue)
                reduce(treeResult, accValue)
              }
              else {
                // REDUCE: On first iteration, store result of tree, do not include value from accum
                val res2 = reduce(treeResult, accValue)
                val mux = math_mux(isFirst, treeResult, res2)
                reduceType(mux) = redType
                mux
              }
            }

            isReduceResult(result) = true
            isReduceStarter(accValue) = true
            register(rFunc.result -> result)  // Lane-specific substitution

            tab -= 1
          }

          logs(s"[Accum-fold $lhs] Unrolling accumulator store")
          inReduction(false){ unrollMap(storeAcc, reduceLanes) }
          void
        }

        val effects = rBlk.summary
        val rpipe = stageEffectful(UnrolledForeach(Seq(bool(true)), cchainRed, rBlk, isRed2, rvs), effects.star)(ctx)
        styleOf(rpipe) = InnerPipe
        levelOf(rpipe) = InnerControl
        tab -= 1
      }
      void
    }

    val rV2 = (fresh[T],fresh[T])
    val rFunc2 = withSubstScope(rV._1 -> rV2._1, rV._2 -> rV2._2){ transformBlock(rFunc) }

    val effects = blk.summary

    val lhs2 = stageEffectful(UnrolledReduce(globalValids, cchainMap, accum, blk, rFunc2, isMap2, mvs, rV2)(mT,mC), effects.star)(ctx)
    transferMetadata(lhs, lhs2)

    logs(s"[Accum-fold] Created reduce ${str(lhs2)}")
    lhs2
  }
  def unrollMemReduceNode[T,C[T]](lhs: Sym[_], rhs: OpMemReduce[T,C])(implicit ctx: SrcCtx) = {
    val OpMemReduce(cchainMap,cchainRed,accum,func,loadRes,loadAcc,reduce,storeAcc,zero,fold,rV,itersMap,itersRed) = rhs
    unrollMemReduce(lhs,f(cchainMap),f(cchainRed),f(accum),f(zero),fold,func,loadRes,loadAcc,reduce,storeAcc,rV,itersMap,itersRed)(rhs.mT,rhs.bT,rhs.mC,ctx)
  }

  override def mirror(lhs: Seq[Sym[_]], rhs: Def): Seq[Exp[_]] = rhs match {
    case op:Op[_] => Seq(cloneOp(lhs.head.asInstanceOf[Sym[Any]], op.asInstanceOf[Op[Any]]))
    case _ => super.mirror(lhs, rhs)
  }

  def cloneOp[A](lhs: Sym[A], rhs: Op[A]): Exp[A] = {
    def cloneOrMirror(lhs: Sym[A], rhs: Op[A])(implicit mA: Type[A], ctx: SrcCtx): Exp[A] = (lhs match {
      case Def(op: EnabledController) => op.mirrorWithEn(f, globalValids)
      case Def(op: EnabledOp[_])      => op.mirrorWithEn(f, globalValid)
      case _ => rhs.mirror(f)
    }).asInstanceOf[Exp[A]]

    logs(c"Cloning $lhs = $rhs")
    strMeta(lhs)

    val (lhs2, isNew) = transferMetadataIfNew(lhs){ cloneOrMirror(lhs, rhs)(mtyp(lhs.tp), lhs.ctx) }

    if (isAccess(lhs) && isNew && inHwScope) {
      registerAccess(lhs, lhs2)
    }

    if (isNew) cloneFuncs.foreach{func => func(lhs2) }
    logs(c"Created ${str(lhs2)}")
    strMeta(lhs2)

    if (cloneFuncs.nonEmpty) {
      dbgs(c"Cloning $lhs = $rhs")
      metadata.get(lhs).foreach{m => dbgs(c" - ${m._1}: ${m._2}") }
      dbgs(c"Created ${str(lhs2)}")
      metadata.get(lhs2).foreach{m => dbgs(c" - ${m._1}: ${m._2}") }
    }

    lhs2
  }
}
