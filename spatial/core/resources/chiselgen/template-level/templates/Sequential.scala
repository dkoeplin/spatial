// See LICENSE.txt for license details.
package templates

import chisel3._
import Utils._
import scala.collection.mutable.HashMap

class Seqpipe(val n: Int, val isFSM: Boolean = false, val retime: Int = 0) extends Module {
  val io = IO(new Bundle {
    val input = new Bundle {
      val enable = Input(Bool())
      val numIter = Input(UInt(32.W))
      val stageDone = Vec(n, Input(Bool()))
      val stageMask = Vec(n, Input(Bool()))
      val rst = Input(Bool())
      val forever = Input(Bool())
      val hasStreamIns = Input(Bool()) // Not used, here for codegen compatibility
      // FSM signals
      val nextState = Input(UInt(32.W))
      val initState = Input(UInt(32.W))
      val doneCondition = Input(Bool())
    }
    val output = new Bundle {
      val done = Output(Bool())
      val stageEnable = Vec(n, Output(Bool()))
      val rst_en = Output(Bool())
      val ctr_inc = Output(Bool())
      // FSM signals
      val state = Output(UInt(32.W))
    }
  })

  if (!isFSM) {
    // 0: INIT, 1: RESET, 2..2+n-1: stages, n: DONE
    val initState = 0
    val resetState = 1
    val firstState = resetState + 1
    val doneState = firstState + n
    val lastState = doneState - 1

    val stateFF = Module(new FF(32))
    stateFF.io.input(0).enable := true.B // TODO: Do we need this line?
    stateFF.io.input(0).init := 0.U
    stateFF.io.input(0).reset := io.input.rst
    val state = stateFF.io.output.data

    // Counter for num iterations
    val maxFF = Module(new FF(32))
    maxFF.io.input(0).enable := io.input.enable
    maxFF.io.input(0).data := io.input.numIter
    maxFF.io.input(0).reset := io.input.rst
    maxFF.io.input(0).init := 0.U
    val max = maxFF.io.output.data

    val ctr = Module(new SingleCounter(1))
    ctr.io.input.enable := io.input.enable & io.input.stageDone(lastState-2) // TODO: Is this wrong? It still works...  
    ctr.io.input.saturate := false.B
    ctr.io.input.max := max
    ctr.io.input.stride := 1.U
    ctr.io.input.start := 0.U
    ctr.io.input.gap := 0.U
    ctr.io.input.reset := io.input.rst | (state === doneState.U)
    val iter = ctr.io.output.count(0)
    io.output.rst_en := (state === resetState.U)

    when(io.input.enable) {
      when(state === initState.U) {
        stateFF.io.input(0).data := resetState.U
        io.output.stageEnable.foreach { s => s := false.B}
      }.elsewhen (state === resetState.U) {
        stateFF.io.input(0).data := Mux(io.input.numIter === 0.U, Mux(io.input.forever, firstState.U, doneState.U), firstState.U)
        io.output.stageEnable.foreach { s => s := false.B}
      }.elsewhen (state < lastState.U) {

        // // Safe but expensive way
        // val doneStageId = (0 until n).map { i => // Find which stage got done signal
        //   Mux(io.input.stageDone(i), UInt(i+1), 0.U) 
        // }.reduce {_+_}
        // when(state === (doneStageId + 1.U)) {
        //   stateFF.io.input(0).data := doneStageId + 2.U
        // }.otherwise {
        //   stateFF.io.input(0).data := state
        // }

        // // Less safe but cheap way
        // val aStageIsDone = io.input.stageDone.reduce { _ | _ } // TODO: Is it safe to assume children behave properly?
        // when(aStageIsDone) {
        //   stateFF.io.input(0).data := state + 1.U
        // }.otherwise {
        //   stateFF.io.input(0).data := state
        // }
        // Correct way
        val stageDones = (0 until n).map{i => (i.U -> {io.input.stageDone(i) | ~io.input.stageMask(i)} )}
        val myStageIsDone = chisel3.util.MuxLookup( (state - firstState.U), false.B, stageDones) 
        when(myStageIsDone) {
          stateFF.io.input(0).data := state + 1.U
        }.otherwise {
          stateFF.io.input(0).data := state
        }

      }.elsewhen (state === lastState.U) {
        when(io.input.stageDone(lastState-2)) {
          when(ctr.io.output.done) {
            stateFF.io.input(0).data := Mux(io.input.forever, firstState.U, doneState.U)
          }.otherwise {
            stateFF.io.input(0).data := firstState.U
          }
        }.otherwise {
          stateFF.io.input(0).data := state
        }

      }.elsewhen (state === doneState.U) {
        stateFF.io.input(0).data := initState.U
      }.otherwise {
        stateFF.io.input(0).data := state
      }
    }.otherwise {
      stateFF.io.input(0).data := initState.U
    }
  //  stateFF.io.input(0).data := nextStateMux.io.out

    // Output logic
    io.output.done := state === doneState.U
    io.output.ctr_inc := io.input.stageDone(n-1) & Utils.delay(~io.input.stageDone(0), 1) // on rising edge
    io.output.stageEnable.zipWithIndex.foreach { case (en, i) => en := (state === (i+2).U) }
    io.output.state := state
  } else { // FSM logic
    // 0: INIT, 1: RESET, 2..2+n-1: stages, n: DONE
    val initState = 0
    val resetState = 1
    val firstState = resetState + 1
    val doneState = firstState + n
    val lastState = doneState - 1

    val stateFF = Module(new FF(32))
    stateFF.io.input(0).enable := true.B // TODO: Do we need this line?
    stateFF.io.input(0).init := 0.U
    stateFF.io.input(0).reset := io.input.rst
    val state = stateFF.io.output.data

    // FSM stuff 
    val stateFSM = Module(new FF(32))
    val doneReg = Module(new SRFF())

    stateFSM.io.input(0).data := io.input.nextState
    stateFSM.io.input(0).init := io.input.initState
    stateFSM.io.input(0).reset := reset
    stateFSM.io.input(0).enable := io.input.enable & state === doneState.U
    io.output.state := stateFSM.io.output.data

    doneReg.io.input.set := io.input.doneCondition & io.input.enable
    doneReg.io.input.reset := ~io.input.enable
    doneReg.io.input.asyn_reset := false.B
    io.output.done := doneReg.io.output.data | (io.input.doneCondition & io.input.enable)

    // Counter for num iterations
    val maxFF = Module(new FF(32))
    maxFF.io.input(0).enable := io.input.enable
    maxFF.io.input(0).data := io.input.numIter
    maxFF.io.input(0).init := 0.U
    maxFF.io.input(0).reset := io.input.rst
    val max = maxFF.io.output.data

    val ctr = Module(new SingleCounter(1))
    ctr.io.input.enable := io.input.enable & io.input.stageDone(lastState-2) // TODO: Is this wrong? It still works...  
    ctr.io.input.reset := (state === doneState.U)
    ctr.io.input.saturate := false.B
    ctr.io.input.max := max
    ctr.io.input.stride := 1.U
    val iter = ctr.io.output.count(0)
    io.output.rst_en := (state === resetState.U)

    when(io.input.enable) {
      when(state === initState.U) {
        stateFF.io.input(0).data := resetState.U
        io.output.stageEnable.foreach { s => s := false.B}
      }.elsewhen (state === resetState.U) {
        stateFF.io.input(0).data := Mux(io.input.numIter === 0.U, Mux(io.input.forever, firstState.U, doneState.U), firstState.U)
        io.output.stageEnable.foreach { s => s := false.B}
      }.elsewhen (state < lastState.U) {

        // // Safe but expensive way
        // val doneStageId = (0 until n).map { i => // Find which stage got done signal
        //   Mux(io.input.stageDone(i), UInt(i+1), 0.U) 
        // }.reduce {_+_}
        // when(state === (doneStageId + 1.U)) {
        //   stateFF.io.input(0).data := doneStageId + 2.U
        // }.otherwise {
        //   stateFF.io.input(0).data := state
        // }

        // Less safe but cheap way
        val aStageIsDone = io.input.stageDone.reduce { _ | _ } // TODO: Is it safe to assume children behave properly?
        when(aStageIsDone) {
          stateFF.io.input(0).data := state + 1.U
        }.otherwise {
          stateFF.io.input(0).data := state
        }

      }.elsewhen (state === lastState.U) {
        when(io.input.stageDone(lastState-2)) {
          when(ctr.io.output.done) {
            stateFF.io.input(0).data := Mux(io.input.forever, firstState.U, doneState.U)
          }.otherwise {
            stateFF.io.input(0).data := firstState.U
          }
        }.otherwise {
          stateFF.io.input(0).data := state
        }

      }.elsewhen (state === doneState.U) {
        stateFF.io.input(0).data := initState.U
      }.otherwise {
        stateFF.io.input(0).data := state
      }
    }.otherwise {
      stateFF.io.input(0).data := initState.U
    }
  //  stateFF.io.input(0).data := nextStateMux.io.out

    // Output logic
    io.output.ctr_inc := io.input.stageDone(n-1) & Utils.delay(~io.input.stageDone(0), 1) // on rising edge
    io.output.stageEnable.zipWithIndex.foreach { case (en, i) => en := (state === (i+2).U) }
  }
}
