package spatial.analysis

import spatial.models.LatencyModel

import scala.collection.mutable

trait ModelingTraversal extends SpatialTraversal { traversal =>
  import IR._

  lazy val latencyModel = new LatencyModel{val IR: traversal.IR.type = traversal.IR }

  protected override def preprocess[S: Type](block: Block[S]) = {
    // latencyOf.updateModel(target.latencyModel) // TODO: Update latency model with target-specific values
    inHwScope = false
    inReduce = false
    super.preprocess(block)
  }

  // --- State
  var inHwScope = false // In hardware scope
  var inReduce = false  // In tight reduction cycle (accumulator update)
  def latencyOf(e: Exp[_]) = {
    // HACK: For now, disable retiming in reduction cycles by making everything have 0 latency
    // This means everything will be purely combinational logic between the accumulator read and write
    val inReductionCycle = reduceType(e).isDefined
    if (inReductionCycle) 0L else {

      if (inHwScope) latencyModel(e, inReduce) else 0L
    }
  }

  // TODO: Could optimize further with dynamic programming
  def latencyOfPipe(b: Block[_]): Long = {
    val scope = getStages(b)
    val paths = mutable.HashMap[Exp[_],Long]()
    //debug(s"Pipe latency $b:")

    def quickDFS(cur: Exp[_]): Long = cur match {
      case Def(d) if scope.contains(cur) && !isGlobal(cur) =>
        //debug(s"Visit $cur in quickDFS")
        val deps = exps(d)
        if (deps.isEmpty) {
          if (effectsOf(cur).isPure) warn(cur.ctx, s"[Compiler] $cur = $d has no dependencies but is not global?")
          latencyOf(cur)
        }
        else {
          latencyOf(cur) + deps.map{e => paths.getOrElseUpdate(e, quickDFS(e))}.max
        }
      case _ => 0L
    }
    if (scope.isEmpty) 0L else exps(b).map{e => paths.getOrElseUpdate(e, quickDFS(e)) }.max
  }
  def latencyOfCycle(b: Block[Any]): Long = {
    val outerReduce = inReduce
    inReduce = true
    val out = latencyOfPipe(b)
    inReduce = outerReduce
    out
  }

  class GetOrElseUpdateFix[K,V](x: mutable.Map[K,V]) {
    def getOrElseAdd(k: K, v: => V): V = if (x.contains(k)) x(k) else { val value = v; x(k) = value; value }
  }
  implicit def getOrUpdateFix[K,V](x: mutable.Map[K,V]): GetOrElseUpdateFix[K,V] = new GetOrElseUpdateFix[K,V](x)


  def pipeLatencies(b: Block[_], oos: Map[Exp[_],Long] = Map.empty): (Map[Exp[_],Long], Long) = {
    val scope = getStages(b).filterNot(s => isGlobal(s))
                            .filter{e => e.tp == VoidType || Bits.unapply(e.tp).isDefined }
                            .map(_.asInstanceOf[Exp[_]]).toSet

    val localReads  = scope.collect{case reader @ LocalReader(reads) => reader -> reads.head.mem }
    val localWrites = scope.collect{case writer @ LocalWriter(writes) => writer -> writes.head.mem }

    val localAccums = localWrites.flatMap{case (writer,writtenMem) =>
      localReads.find{case (reader,readMem) => readMem == writtenMem && writer.dependsOn(reader) }.map{x => (x._1,writer,writtenMem) }
    }
    val accumReads = localAccums.map(_._1)
    val accumWrites = localAccums.map(_._2)

    val paths = mutable.HashMap[Exp[_],Long]() ++ oos
    val cycles = mutable.HashMap[Exp[_],Set[Exp[_]]]()

    accumReads.foreach{reader => cycles(reader) = Set(reader) }

    def fullDFS(cur: Exp[_]): Long = cur match {
      case Def(d) if scope.contains(cur) =>
        val deps = scope intersect d.allInputs.toSet // Handles effect scheduling, even though there's no data to pass

        if (deps.nonEmpty) {
          val dlys = deps.map{e => paths.getOrElseAdd(e, fullDFS(e)) }
          val critical = dlys.max

          val cycleSyms = deps intersect cycles.keySet
          if (cycleSyms.nonEmpty) {
            cycles(cur) = cycleSyms.flatMap(cycles) + cur
            dbgs(c"cycle deps of $cur: ${cycles(cur)}")
          }

          dbgs(c"${str(cur)} [delay = max(" + dlys.mkString(", ") + s") + ${latencyOf(cur)}]" + (if (cycles.contains(cur)) "[cycle]" else ""))
          critical + latencyOf(cur) // TODO + inputDelayOf(cur) -- factor in delays which are external to reduction cycles
        }
        else {
          dbgs(c"${str(cur)}" + (if (cycles.contains(cur)) "[cycle]" else ""))
          latencyOf(cur)
        }

      case s => paths.getOrElse(s, 0L) // Get preset out of scope delay, or assume 0 offset
    }

    // Perform backwards pass to push unnecessary delays out of reduction cycles
    // This can create extra registers, but decreases the initiation interval of the cycle
    def reverseDFS(cur: Exp[_], scope: Set[Exp[_]]): Unit = cur match {
      case s: Sym[_] if scope contains cur =>
        val forward = s.dependents
        if (forward.nonEmpty) {
          val earliestConsumer = forward.map{e => paths.getOrElse(e, 0L) - latencyOf(e) }.min
          paths(cur) = Math.max(earliestConsumer, paths.getOrElse(cur, 0L))
        }
        getDef(s).foreach{d => d.allInputs.foreach{in => reverseDFS(in, scope) }}

      case _ => // Do nothing
    }

    if (scope.nonEmpty) {
      // Perform forwards pass for normal data dependencies
      val deps = exps(b).toSet intersect scope
      deps.foreach{e => paths.getOrElseAdd(e, fullDFS(e)) }

      // TODO: What to do in case where a node is contained in multiple cycles?
      accumWrites.zipWithIndex.foreach{case (writer,i) =>
        val cycle = cycles(writer)
        val cycleList = cycle.toList
        dbgs(s"Cycle #$i: " + cycleList.map{s => c"($s, ${paths(s)})"}.mkString(", "))

        reverseDFS(writer, cycle)

        /*cycle.sliding(2).foreach{
          case List(high,low) =>
            // Move the lower to just before the higher
            paths(low) = Math.max(paths(high) - latencyOf(high), paths(low))
          case _ =>
        }*/

        dbgs(s"Cycle #$i: " + cycleList.map{s => c"($s, ${paths(s)})"}.mkString(", "))
      }
    }

    val initiationInterval = if (localAccums.isEmpty) 1L else localAccums.map{case (read,write,_) => paths(write) - paths(read) }.max

    (paths.toMap, Math.max(initiationInterval, 1L))
  }

}

