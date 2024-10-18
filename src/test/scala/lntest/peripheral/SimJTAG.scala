package lntest.peripheral

import chisel3.experimental.{ExtModule, IntParam}
import chisel3._
import chisel3.util._
import freechips.rocketchip.jtag.JTAGIO
import org.chipsalliance.cde.config.{Field, Parameters}

class SimJTAG(tickDelay: Int = 50) extends ExtModule(Map("TICK_DELAY" -> IntParam(tickDelay)))
  with HasExtModuleResource {

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Reset()))
  val jtag = IO(new JTAGIO(hasTRSTn = true))
  val enable = IO(Input(Bool()))
  val init_done = IO(Input(Bool()))
  val exit = IO(Output(UInt(32.W)))

  def connect(dutio: JTAGIO, tbclock: Clock, tbreset: Reset, done: Bool, tbsuccess: Bool, jtagEnable:Boolean) = {
    if (dutio.TRSTn.isDefined && jtagEnable) {
      dutio.TRSTn.get := jtag.TRSTn.getOrElse(false.B) || !tbreset.asBool
    }
    dutio.TCK := jtag.TCK
    dutio.TMS := jtag.TMS
    dutio.TDI := jtag.TDI
    jtag.TDO := dutio.TDO

    clock := tbclock
    reset := tbreset

    enable    := jtagEnable.B
    init_done := done

    // Success is determined by the gdbserver
    // which is controlling this simulation.
    tbsuccess := exit === 1.U
    when (exit >= 2.U) {
      printf("*** FAILED *** (exit code = %d)\n", exit >> 1.U)
      stop()
    }
  }
}
