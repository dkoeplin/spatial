package spatial

import argon.codegen.Codegen
import argon.codegen.scalagen._
import argon.codegen.chiselgen._
import argon.codegen.dotgen._
import argon.codegen.cppgen._
import spatial.codegen.dotgen._
import argon.traversal.IRPrinter
import argon._
import forge._
import org.virtualized.EmptyContext
import spatial.api._
import spatial.dse._
import spatial.analysis._
import spatial.transform._
import spatial.codegen.scalagen._
import spatial.codegen.chiselgen._
import spatial.codegen.pirgen._
import spatial.codegen.cppgen._

protected trait SpatialExp
  extends ArgonExp with SpatialExceptions with MatrixExp
  with DebuggingExp with TemplatesExp with BitOpsExp with FileIOExp
  with ControllerExp with CounterExp with DRAMExp with DRAMTransferExp with FIFOExp with FILOExp with HostTransferExp with MathExp
  with MemoryExp with ParameterExp with RangeExp with RegExp with SRAMExp with StagedUtilExp with UnrolledExp with VectorExp
  with StreamExp with PinExp with AlteraVideoExp with ShiftRegExp with LUTsExp
  with LineBufferExp with RegisterFileExp with SwitchExp with StateMachineExp with EnabledPrimitivesExp
  with NodeClasses with NodeUtils with ParameterRestrictions with SpatialMetadataExp with BankingMetadataExp
  with Aliases with StreamTransfersExp

trait SpatialImplicits{this: SpatialApi =>
  // HACK: Insert Void where required to make programs not have to include () at the end of ... => Void functions
  implicit def insert_void[T:Meta](x: T): Void = Unit()(EmptyContext)

  // Hacks required to allow .to[T] syntax on various primitive types
  // Naming is apparently important here (has to have same names as in Predef)
  implicit class longWrapper(x: scala.Long) {
    @api def to[B:Meta](implicit cast: Cast[scala.Long,B]): B = cast(x)
  }
  implicit class floatWrapper(x: scala.Float) {
    @api def to[B:Meta](implicit cast: Cast[scala.Float,B]): B = cast(x)
  }
  implicit class doubleWrapper(x: scala.Double) {
    @api def to[B:Meta](implicit cast: Cast[scala.Double,B]): B = cast(x)
  }
}

protected trait SpatialApi extends SpatialExp
  with ArgonApi with MatrixApi
  with DebuggingApi with BitsOpsApi
  with ControllerApi with CounterApi with DRAMApi with DRAMTransferApi with FIFOApi with FILOApi with HostTransferApi with MathApi
  with MemoryApi with ParameterApi with RangeApi with RegApi with SRAMApi with StagedUtilApi with UnrolledApi with VectorApi
  with StreamApi with PinApi with AlteraVideoApi with ShiftRegApi with LUTsApi
  with LineBufferApi with RegisterFileApi with SwitchApi with StateMachineApi with EnabledPrimitivesApi
  with SpatialMetadataApi with BankingMetadataApi with SpatialImplicits with FileIOApi
  with StreamTransfersApi
 

protected trait ScalaGenSpatial extends ScalaCodegen with ScalaFileGen
  with ScalaGenArray with ScalaGenSpatialArrayExt with ScalaGenSpatialBool with ScalaGenSpatialFixPt with ScalaGenSpatialFltPt
  with ScalaGenHashMap with ScalaGenIfThenElse with ScalaGenStructs with ScalaGenSpatialStruct
  with ScalaGenText with ScalaGenVoid with ScalaGenFunction with ScalaGenVariables
  with ScalaGenDebugging with ScalaGenFILO
  with ScalaGenController with ScalaGenCounter with ScalaGenDRAM with ScalaGenFIFO with ScalaGenHostTransfer with ScalaGenMath
  with ScalaGenRange with ScalaGenReg with ScalaGenSRAM with ScalaGenUnrolled with ScalaGenVector
  with ScalaGenStream
  with ScalaGenLineBuffer with ScalaGenRegFile with ScalaGenStateMachine with ScalaGenFileIO with ScalaGenShiftReg with ScalaGenLUTs {

  override val IR: SpatialCompiler

  override def copyDependencies(out: String): Unit = {
    dependencies ::= FileDep("scalagen", "Makefile", "../")
    dependencies ::= FileDep("scalagen", "run.sh", "../")
    dependencies ::= FileDep("scalagen", "build.sbt", "../")
    super.copyDependencies(out)
  }
}

protected trait ChiselGenSpatial extends ChiselCodegen with ChiselFileGen
  with ChiselGenBool with ChiselGenVoid with ChiselGenFixPt with ChiselGenFltPt
  with ChiselGenCounter with ChiselGenReg with ChiselGenSRAM with ChiselGenFIFO 
  with ChiselGenIfThenElse with ChiselGenController with ChiselGenMath with ChiselGenText
  with ChiselGenDRAM with ChiselGenHostTransfer with ChiselGenUnrolled with ChiselGenVector
  with ChiselGenArray with ChiselGenAlteraVideo with ChiselGenStream with ChiselGenStructs with ChiselGenLineBuffer
  with ChiselGenRegFile with ChiselGenStateMachine with ChiselGenFileIO with ChiselGenRetiming with ChiselGenFILO {

  override val IR: SpatialCompiler
}

protected trait PIRGenSpatial extends PIRCodegen with PIRFileGen with PIRGenController {
  override val IR: SpatialCompiler
}

protected trait CppGenSpatial extends CppCodegen with CppFileGen
  with CppGenBool with CppGenVoid with CppGenFixPt with CppGenFltPt
  with CppGenCounter with CppGenReg with CppGenSRAM with CppGenFIFO 
  with CppGenIfThenElse with CppGenController with CppGenMath with CppGenFringeCopy with CppGenText
  with CppGenDRAM with CppGenHostTransfer with CppGenUnrolled with CppGenVector
  with CppGenArray with CppGenArrayExt with CppGenRange with CppGenAlteraVideo with CppGenStream
  with CppGenHashMap with CppGenStructs with CppGenDebugging with CppGenFileIO with CppGenFunction 
  with CppGenVariables{

  override val IR: SpatialCompiler
}

protected trait TreeWriter extends TreeGenSpatial {
  override val IR: SpatialCompiler
}

protected trait DotGenSpatial extends DotCodegen with DotFileGen
  with DotGenEmpty
  with DotGenBool with DotGenVoid with DotGenFixPt with DotGenFltPt
  with DotGenCounter with DotGenReg with DotGenSRAM with DotGenFIFO
  with DotGenIfThenElse with DotGenController with DotGenMath with DotGenText
  with DotGenDRAM with DotGenHostTransfer with DotGenUnrolled with DotGenVector
  with DotGenArray with DotGenAlteraVideo with DotGenStream with DotGenRetiming with DotGenStruct{

  override val IR: SpatialCompiler

  override def copyDependencies(out: String): Unit = {
    dependencies ::= FileDep("dotgen", "run.sh", "../")
    super.copyDependencies(out)
  }
}


protected trait SpatialCompiler extends CompilerCore with SpatialApi with PIRCommonExp { self =>
  lazy val printer = new IRPrinter {val IR: self.type = self }

  // Traversals
  lazy val scalarAnalyzer = new ScalarAnalyzer { val IR: self.type = self }
//lazy val constFolding   = new ConstantFolding { val IR: self.type = self }
  lazy val levelAnalyzer  = new PipeLevelAnalyzer { val IR: self.type = self }
  lazy val dimAnalyzer    = new DimensionAnalyzer { val IR: self.type = self }

  lazy val switchInsert   = new SwitchTransformer { val IR: self.type = self }
  lazy val unitPipeInsert = new UnitPipeTransformer { val IR: self.type = self }

  lazy val affineAnalyzer = new SpatialAccessAnalyzer { val IR: self.type = self }
  lazy val ctrlAnalyzer   = new ControlSignalAnalyzer { val IR: self.type = self }

  lazy val regCleanup     = new RegisterCleanup { val IR: self.type = self }
  lazy val regReadCSE     = new RegReadCSE { val IR: self.type = self }

  lazy val memAnalyzer    = new MemoryAnalyzer { val IR: self.type = self; def localMems = ctrlAnalyzer.localMems }
  lazy val paramAnalyzer  = new ParameterAnalyzer{val IR: self.type = self }

  lazy val scopeCheck     = new ScopeCheck { val IR: self.type = self }

  lazy val latencyAnalyzer = new LatencyAnalyzer { val IR: self.type = self }

  lazy val controlSanityCheck = new ControllerSanityCheck { val IR: self.type = self }

  lazy val retiming = new PipeRetimer { val IR: self.type = self }

  lazy val dse = new DSE {
    val IR: self.type = self
    def restricts  = paramAnalyzer.restrict
    def tileSizes  = paramAnalyzer.tileSizes
    def parFactors = paramAnalyzer.parFactors
    def localMems  = ctrlAnalyzer.localMems
    def metapipes  = ctrlAnalyzer.metapipes
    def top = ctrlAnalyzer.top.get
  }

  lazy val transferExpand = new TransferSpecialization { val IR: self.type = self }

  lazy val reduceAnalyzer = new ReductionAnalyzer { val IR: self.type = self }

  lazy val uctrlAnalyzer  = new UnrolledControlAnalyzer { val IR: self.type = self }

  lazy val switchFlatten  = new SwitchFlattener { val IR: self.type = self }

  lazy val rewriter       = new RewriteTransformer { val IR: self.type = self }

  lazy val unroller       = new UnrollingTransformer { val IR: self.type = self }

  lazy val bufferAnalyzer = new BufferAnalyzer { val IR: self.type = self; def localMems = uctrlAnalyzer.localMems }
  lazy val streamAnalyzer = new StreamAnalyzer { 
    val IR: self.type = self ;
    def streamPipes = uctrlAnalyzer.streampipes
    def streamEnablers = uctrlAnalyzer.streamEnablers
    def streamHolders = uctrlAnalyzer.streamHolders 
    def streamLoadCtrls = uctrlAnalyzer.streamLoadCtrls 
    def streamParEnqs = uctrlAnalyzer.streamParEnqs
  }

  lazy val pirRetimer = new PIRHackyRetimer { val IR: self.type  = self }
  lazy val pirTiming  = new PIRHackyLatencyAnalyzer { val IR: self.type = self }

  lazy val argMapper  = new ArgMappingAnalyzer {
    val IR: self.type = self
    def memStreams = uctrlAnalyzer.memStreams
    def argPorts = uctrlAnalyzer.argPorts
    def genericStreams = uctrlAnalyzer.genericStreams
  }

  lazy val scalagen = new ScalaGenSpatial { val IR: self.type = self; def localMems = uctrlAnalyzer.localMems }
  lazy val chiselgen = new ChiselGenSpatial { val IR: self.type = self }
  lazy val pirgen = new PIRGenSpatial { val IR: self.type = self }
  lazy val cppgen = new CppGenSpatial { val IR: self.type = self }
  lazy val treegen = new TreeGenSpatial { val IR: self.type = self }
  lazy val dotgen = new DotGenSpatial { val IR: self.type = self }

  def codegenerators = passes.collect{case x: Codegen => x}

  // Traversal schedule
  override def createTraversalSchedule() = {
    passes += printer
    passes += scalarAnalyzer    // Perform bound and global analysis
    passes += scopeCheck        // Check that illegal host values are not used in the accel block
    passes += levelAnalyzer     // Initial pipe style annotation fixes
    passes += dimAnalyzer       // Correctness checks for onchip and offchip dimensions

    // --- Unit Pipe Insertion
    passes += printer
    passes += switchInsert      // Change nested if-then-else statements to Switch controllers
    passes += printer
    passes += unitPipeInsert    // Wrap primitives in outer controllers
    passes += printer
    passes += regReadCSE        // CSE register reads in inner pipelines
    passes += printer

    // --- Pre-Reg Cleanup
    passes += ctrlAnalyzer      // Control signal analysis

    // --- Register cleanup
    passes += printer
    passes += regCleanup        // Remove unused registers and corresponding reads/writes created in unit pipe transform
    passes += printer

    // --- Pre-DSE Analysis
    passes += scalarAnalyzer    // Bounds / global analysis
    passes += affineAnalyzer    // Memory access patterns
    passes += ctrlAnalyzer      // Control signal analysis
    passes += memAnalyzer       // Memory banking/buffering

    // --- DSE
    passes += dse               // TODO: Design space exploration

    // --- Post-DSE Expansion
    // NOTE: Small compiler pass ordering issue here:
    // We may need bound information during node expansion,
    // but we also need to reanalyze bounds to account for expanded nodes
    // For now just doing it twice
    passes += scalarAnalyzer    // Bounds / global analysis
    passes += printer
    passes += transferExpand    // Expand burst loads/stores from single abstract nodes
    passes += levelAnalyzer     // Pipe style annotation fixes after expansion

    // --- Post-Expansion Cleanup
    passes += printer
    passes += regReadCSE        // CSE register reads in inner pipelines
    passes += scalarAnalyzer    // Bounds / global analysis
    passes += ctrlAnalyzer      // Control signal analysis

    passes += printer
    passes += regCleanup        // Remove unused registers and corresponding reads/writes created in unit pipe transform
    passes += printer

    // --- Pre-Unrolling Analysis
    passes += ctrlAnalyzer      // Control signal analysis
    passes += affineAnalyzer    // Memory access patterns
    passes += reduceAnalyzer    // Reduce/accumulator specialization
    passes += memAnalyzer       // Finalize banking/buffering

    // TODO: models go here
    passes += latencyAnalyzer

    // --- Design Elaboration
    passes += printer
    passes += switchFlatten     // Switch inlining for simplification / optimization

    passes += printer
    if (SpatialConfig.enablePIRSim) passes += pirRetimer

    passes += printer
    passes += unroller          // Unrolling
    passes += printer
    passes += uctrlAnalyzer     // Readers/writers for CSE
    passes += printer
    passes += regReadCSE        // CSE register reads in inner pipelines
    passes += printer

    passes += uctrlAnalyzer     // Analysis for unused register reads
    passes += printer
    passes += regCleanup        // Duplicate register reads for each use
    passes += rewriter          // Post-unrolling rewrites (e.g. enabled register writes)
    passes += printer

    // --- Retiming
    if (SpatialConfig.enableRetiming)   passes += retiming // Add delay shift registers where necessary
    passes += printer

    // --- Post-Unroll Analysis
    passes += uctrlAnalyzer     // Control signal analysis (post-unrolling)
    passes += printer
    passes += bufferAnalyzer    // Set top controllers for n-buffers
    passes += streamAnalyzer    // Set stream pipe children fifo dependencies
    passes += argMapper         // Get address offsets for each used DRAM object
    passes += latencyAnalyzer   // Get delay lengths of inner pipes (used for retiming control signals)
    if (SpatialConfig.enablePIRSim) passes += pirTiming // PIR delays (retiming control signals)
    passes += printer

    // --- Sanity Checks
    passes += scopeCheck        // Check that illegal host values are not used in the accel block
    passes += controlSanityCheck

    // --- Code generation
    if (SpatialConfig.enableTree)  passes += treegen
    if (SpatialConfig.enableSim)   passes += scalagen
    if (SpatialConfig.enableSynth) passes += cppgen
    if (SpatialConfig.enableSynth) passes += chiselgen
    if (SpatialConfig.enableDot)   passes += dotgen
    if (SpatialConfig.enablePIR)   passes += pirgen
  }
}

protected trait SpatialIR extends SpatialCompiler
protected trait SpatialLib extends LibCore // Actual library implementation goes here

trait SpatialApp extends AppCore {
  import spatial.targets._

  private def __target: FPGATarget = Targets.targets.find(_.name == SpatialConfig.targetName).getOrElse{ DefaultTarget }
  def target = __target

  val IR: SpatialIR = new SpatialIR { def target = SpatialApp.this.target }
  val Lib: SpatialLib = new SpatialLib { }

  override protected def onException(t: Throwable): Unit = {
    super.onException(t)
    IR.error("If you'd like, you can submit this log and your code in a bug report at: ")
    IR.error("  https://github.com/stanford-ppl/spatial-lang/issues")
    IR.error("and we'll try to fix it as soon as we can.")
  }

  final override def main(sargs: Array[String]): Unit = super.main(sargs)

  override def parseArguments(args: Seq[String]): Unit = {
    SpatialConfig.init()
    val parser = new SpatialArgParser
    parser.parse(args)
  }
}

