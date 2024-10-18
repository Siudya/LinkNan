/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package lntest.top

import org.chipsalliance.cde.config.Parameters
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3._
import chisel3.util.ReadyValidIO
import lntest.peripheral.{AXI4MemorySlave, SimJTAG}
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import xs.utils.{FileRegisters, GTimer}
import difftest._
import circt.stage.ChiselStage
import linknan.generator.{Generator, PrefixKey, RemoveCoreKey}
import linknan.soc.LNTop
import xs.utils.perf.DebugOptionsKey

class SimTop(implicit p: Parameters) extends Module {
  private val debugOpts = p(DebugOptionsKey)

  private val soc = Module(new LNTop)
  private val l_simMMIO = LazyModule(new SimMMIO(soc.io.cfg.params, soc.io.dma.params))
  private val simMMIO = Module(l_simMMIO.module)

  private val periCfg =  simMMIO.cfg.head
  private val periDma = simMMIO.dma.head
  private val socCfg =  soc.io.cfg
  private val socDma = soc.io.dma

  private def connByName(sink:ReadyValidIO[Bundle], src:ReadyValidIO[Bundle]):Unit = {
    sink.valid := src.valid
    src.ready := sink.ready
    sink.bits := DontCare
    val recvMap = sink.bits.elements.map(e => (e._1.toLowerCase, e._2))
    val sendMap = src.bits.elements.map(e => (e._1.toLowerCase, e._2))
    for((name, data) <- recvMap) {
      if(sendMap.contains(name)) data := sendMap(name).asTypeOf(data)
    }
  }
  connByName(periCfg.aw, socCfg.aw)
  connByName(periCfg.ar, socCfg.ar)
  connByName(periCfg.w, socCfg.w)
  connByName(socCfg.r, periCfg.r)
  connByName(socCfg.b, periCfg.b)

  connByName(socDma.aw, periDma.aw)
  connByName(socDma.ar, periDma.ar)
  connByName(socDma.w, periDma.w)
  connByName(periDma.r, socDma.r)
  connByName(periDma.b, socDma.b)


  private val l_simAXIMem = AXI4MemorySlave(
    l_simMMIO.dma_node,
    16L * 1024 * 1024 * 1024,
    useBlackBox = true,
    dynamicLatency = debugOpts.UseDRAMSim,
    pureDram = p(RemoveCoreKey)
  )
  private val simAXIMem = Module(l_simAXIMem.module)
  private val memAxi = l_simAXIMem.io_axi4.head
  connByName(memAxi.aw, soc.io.ddr.aw)
  connByName(memAxi.ar, soc.io.ddr.ar)
  connByName(memAxi.w, soc.io.ddr.w)
  connByName(soc.io.ddr.r, memAxi.r)
  connByName(soc.io.ddr.b, memAxi.b)

  val freq = 100
  val cnt = RegInit((freq - 1).U)
  val tick = cnt < (freq / 2).U
  cnt := Mux(cnt === 0.U, (freq - 1).U, cnt - 1.U)

  soc.io.rtc_clock := tick
  soc.io.noc_clock := clock
  soc.io.cluster_clocks.foreach(_ := clock)
  soc.io.soc_clock := clock
  soc.io.reset := (reset.asBool || soc.io.ndreset).asAsyncReset
  soc.io.ext_intr := simMMIO.io.interrupt.intrVec
  soc.dft := DontCare
  soc.dft.reset.lgc_rst_n := true.B.asAsyncReset
  soc.io.default_reset_vector := 0x10000000L.U
  soc.io.chip := 0.U

  val success = Wire(Bool())
  val jtag = Module(new SimJTAG(tickDelay=3))
  soc.io.jtag.reset := reset.asAsyncReset
  jtag.connect(soc.io.jtag.jtag, clock, reset.asBool, !reset.asBool, success, jtagEnable = true)
  soc.io.jtag.mfr_id := 0.U(11.W)
  soc.io.jtag.part_number := 0.U(16.W)
  soc.io.jtag.version := 0.U(4.W)

  val io = IO(new Bundle(){
    val logCtrl = new LogCtrlIO
    val perfInfo = new PerfInfoIO
    val uart = new UARTIO
  })
  val core = if(p(RemoveCoreKey)) Some(IO(soc.core.get.cloneType)) else None
  core.foreach(_ <> soc.core.get)

  simMMIO.io.uart <> io.uart

  if (!debugOpts.FPGAPlatform && debugOpts.EnablePerfDebug) {
    val timer = Wire(UInt(64.W))
    val logEnable = Wire(Bool())
    val clean = Wire(Bool())
    val dump = Wire(Bool())
    timer := GTimer()
    logEnable := (timer >= io.logCtrl.log_begin) && (timer < io.logCtrl.log_end)
    clean := RegNext(io.perfInfo.clean, false.B)
    dump := io.perfInfo.dump
    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)
  }

  if(!p(RemoveCoreKey)) DifftestModule.finish("XiangShan")
}

object SimGenerator extends App {
  val (config, firrtlOpts) = SimArgParser(args)
  xs.utils.GlobalData.prefix = config(PrefixKey)
  difftest.GlobalData.prefix = config(PrefixKey)
  (new ChiselStage).execute(firrtlOpts, Generator.firtoolOps ++ Seq(
    ChiselGeneratorAnnotation(() => {
      DisableMonitors(p => new SimTop()(p))(config)
    })
  ))
  FileRegisters.write(filePrefix = config(PrefixKey) + "LNTop.")
}
