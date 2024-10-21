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
import lntest.peripheral.SimJTAG
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import xs.utils.{FileRegisters, GTimer}
import difftest._
import circt.stage.ChiselStage
import linknan.generator.{Generator, PrefixKey, RemoveCoreKey}
import linknan.soc.LNTop
import xijiang.tfb.TrafficBoardFileManager
import xs.utils.perf.DebugOptionsKey
import zhujiang.ZJParametersKey
import zhujiang.axi.AxiBundle

class SimTop(implicit p: Parameters) extends Module {
  private val debugOpts = p(DebugOptionsKey)

  private val soc = Module(new LNTop)
  private val l_simMMIO = if(p(RemoveCoreKey)) None else Some(LazyModule(new SimMMIO(soc.io.cfg.params, soc.io.dma.params)))
  private val simMMIO = if(p(RemoveCoreKey)) None else Some(Module(l_simMMIO.get.module))

  val io = IO(new Bundle(){
    val logCtrl = if(p(RemoveCoreKey)) None else Some(new LogCtrlIO)
    val perfInfo = if(p(RemoveCoreKey)) None else Some(new PerfInfoIO)
    val uart = if(p(RemoveCoreKey)) None else Some(new UARTIO)
    val dma = if(p(RemoveCoreKey)) Some(Flipped(new AxiBundle(soc.io.dma.params))) else None
    val cfg = if(p(RemoveCoreKey)) Some(new AxiBundle(soc.io.cfg.params)) else None
  })

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

  if(p(RemoveCoreKey)) {
    io.dma.get <> soc.io.dma
    io.cfg.get <> soc.io.cfg
    soc.io.ext_intr := 0.U
  } else {
    soc.io.ext_intr := simMMIO.get.io.interrupt.intrVec
    val periCfg = simMMIO.get.cfg.head
    val periDma = simMMIO.get.dma.head
    val socCfg =  soc.io.cfg
    val socDma = soc.io.dma

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
  }

  private val l_simAXIMem = LazyModule(new DummyDramMoudle(soc.io.ddr.params))
  private val simAXIMem = Module(l_simAXIMem.module)
  private val memAxi = simAXIMem.axi.head
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
  soc.dft := DontCare
  soc.dft.reset.lgc_rst_n := true.B.asAsyncReset
  soc.io.default_reset_vector := 0x10000000L.U
  soc.io.chip := 0.U

  if(p(RemoveCoreKey)) {
    soc.io.jtag := DontCare
  } else {
    val success = Wire(Bool())
    val jtag = Module(new SimJTAG(tickDelay = 3))
    soc.io.jtag.reset := reset.asAsyncReset
    jtag.connect(soc.io.jtag.jtag, clock, reset.asBool, !reset.asBool, success, jtagEnable = true)
    soc.io.jtag.mfr_id := 0.U(11.W)
    soc.io.jtag.part_number := 0.U(16.W)
    soc.io.jtag.version := 0.U(4.W)
    simMMIO.get.io.uart <> io.uart.get
  }

  val core = if(p(RemoveCoreKey)) Some(IO(soc.core.get.cloneType)) else None
  core.foreach(_ <> soc.core.get)

  if (!debugOpts.FPGAPlatform && debugOpts.EnablePerfDebug && !p(RemoveCoreKey)) {
    val timer = Wire(UInt(64.W))
    val logEnable = Wire(Bool())
    val clean = Wire(Bool())
    val dump = Wire(Bool())
    timer := GTimer()
    logEnable := (timer >= io.logCtrl.get.log_begin) && (timer < io.logCtrl.get.log_end)
    clean := RegNext(io.perfInfo.get.clean, false.B)
    dump := io.perfInfo.get.dump
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
  if(config(ZJParametersKey).tfbParams.isDefined) TrafficBoardFileManager.release("generated-src", "generated-src", config)
  FileRegisters.write(filePrefix = config(PrefixKey) + "LNTop.")
}
