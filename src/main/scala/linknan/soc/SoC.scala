package linknan.soc

import chisel3._
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import linknan.cluster.CpuClusterMirror
import linknan.generator.ClusterPfxKey
import zhujiang.{ZJModule, ZJRawModule, Zhujiang}
import org.chipsalliance.cde.config.{Field, Parameters}
import sifive.enterprise.firrtl.NestedPrefixModulesAnnotation
import zhujiang.axi.AxiBundle
import zhujiang.device.cluster.interconnect.DftWires
import zhujiang.device.uncore.UncoreComplex

class SoC(implicit p:Parameters) extends ZJRawModule with ImplicitClock with ImplicitReset {
  override protected val implicitClock = Wire(Clock())
  implicitClock := false.B.asClock
  override protected val implicitReset = Wire(AsyncReset())
  private val mod = this.toNamed
  annotate(new ChiselAnnotation {
    def toFirrtl = NestedPrefixModulesAnnotation(mod, zjParams.modulePrefix, inclusive = true)
  })

  private val noc = Module(new Zhujiang)
  private val clusterNum = noc.io.cluster.length
  private val uncore = Module(new UncoreComplex(noc.io.soc.cfg.node, noc.io.soc.dma.node))
  uncore.io.async.cfg <> noc.io.soc.cfg
  noc.io.soc.dma <> uncore.io.async.dma

  val io = IO(new Bundle{
    val reset = Input(AsyncReset())
    val cluster_clocks = Input(Vec(clusterNum, Clock()))
    val soc_clock = Input(Clock())
    val noc_clock = Input(Clock())
    val rtc_clock = Input(Bool())
    val ext_intr = Input(UInt(zjParams.externalInterruptNum.W))
    val chip = Input(UInt(nodeAidBits.W))
    val ddr = new AxiBundle(noc.io.ddr.params)
    val cfg = new AxiBundle(uncore.io.ext.cfg.params)
    val dma = Flipped(new AxiBundle(uncore.io.ext.dma.params))
    val ndreset = Output(Bool())
    val default_reset_vector = Input(UInt(raw.W))
  })
  val jtag = IO(chiselTypeOf(uncore.io.debug.systemjtag.get))
  val dft = IO(Input(new DftWires))
  implicitReset := io.reset

  io.ddr <> noc.io.ddr
  noc.io.chip := io.chip
  noc.dft := dft
  noc.clock := io.noc_clock

  uncore.io.ext.dma <> io.dma
  io.cfg <> uncore.io.ext.cfg
  uncore.io.ext.timerTick := io.rtc_clock
  uncore.io.ext.intr := io.ext_intr
  uncore.io.chip := io.chip
  uncore.io.debug.systemjtag.foreach(_ <> jtag)
  uncore.clock := io.soc_clock
  uncore.dft := dft
  uncore.io.debug.dmactiveAck := uncore.io.debug.dmactive
  uncore.io.debug.clock := DontCare
  uncore.io.debug.reset := DontCare
  io.ndreset := uncore.io.debug.ndreset

  for((icn, i) <- noc.io.cluster.zipWithIndex) {
    val clusterId = icn.node.clusterId
    val cc = Module(new CpuClusterMirror(icn.node))
    cc.suggestName(s"cluster_$i")
    cc.io.icn.async <> icn
    cc.io.icn.osc_clock := io.cluster_clocks(i)
    cc.io.icn.dft := dft
    for(i <- 0 until icn.node.cpuNum) {
      val cid = clusterId + i
      cc.io.icn.misc.msip(i) := uncore.io.cpu.msip(cid)
      cc.io.icn.misc.mtip(i) := uncore.io.cpu.mtip(cid)
      cc.io.icn.misc.meip(i) := uncore.io.cpu.meip(cid)
      cc.io.icn.misc.seip(i) := uncore.io.cpu.seip(cid)
      cc.io.icn.misc.dbip(i) := uncore.io.cpu.dbip(cid)
      cc.io.icn.misc.mhartid(i) := Cat(io.chip, cid.U((clusterIdBits - nodeAidBits).W))
      uncore.io.resetCtrl.hartIsInReset(cid) := cc.io.icn.misc.resetState(i)
      cc.io.icn.misc.resetVector(i) := io.default_reset_vector
      if(cid == 0) {
        cc.io.icn.misc.resetEnable(i) := true.B
      } else {
        cc.io.icn.misc.resetEnable(i) := false.B
      }
    }
  }
}
