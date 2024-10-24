package linknan.soc

import chisel3._
import chisel3.experimental.hierarchy.{Definition, Instance}
import chisel3.experimental.{ChiselAnnotation, annotate}
import chisel3.util._
import linknan.cluster.{CoreBlockTestIO, CpuCluster}
import linknan.generator.{PrefixKey, RemoveCoreKey}
import linknan.soc.uncore.UncoreComplex
import zhujiang.{ZJRawModule, Zhujiang}
import org.chipsalliance.cde.config.Parameters
import sifive.enterprise.firrtl.NestedPrefixModulesAnnotation
import zhujiang.axi.AxiBundle
import zhujiang.device.cluster.interconnect.DftWires

class LNTop(implicit p:Parameters) extends ZJRawModule with ImplicitClock with ImplicitReset {
  override protected val implicitClock = Wire(Clock())
  implicitClock := false.B.asClock
  override protected val implicitReset = Wire(AsyncReset())
  private val mod = this.toNamed
  annotate(new ChiselAnnotation {
    def toFirrtl = NestedPrefixModulesAnnotation(mod, p(PrefixKey), inclusive = true)
  })
  private val noc = Module(new Zhujiang)
  private val clusterNum = noc.io.cluster.length
  private val uncore = Module(new UncoreComplex(noc.io.soc.cfg.node, noc.io.soc.dma.node))
  private val ddrSys = Module(new MemoryComplex(noc.io.ddr.cfg.node, noc.io.ddr.dat.node))
  uncore.io.async.cfg <> noc.io.soc.cfg
  noc.io.soc.dma <> uncore.io.async.dma
  ddrSys.io.icn.cfg <> noc.io.ddr.cfg
  ddrSys.io.icn.mem <> noc.io.ddr.dat
  ddrSys.reset := noc.io.ddr.reset

  val io = IO(new Bundle{
    val reset = Input(AsyncReset())
    val cluster_clocks = Input(Vec(clusterNum, Clock()))
    val soc_clock = Input(Clock())
    val noc_clock = Input(Clock())
    val rtc_clock = Input(Bool())
    val ext_intr = Input(UInt(zjParams.externalInterruptNum.W))
    val chip = Input(UInt(nodeAidBits.W))
    val ddr = new AxiBundle(ddrSys.io.ddr.params)
    val cfg = new AxiBundle(uncore.io.ext.cfg.params)
    val dma = Flipped(new AxiBundle(uncore.io.ext.dma.params))
    val ndreset = Output(Bool())
    val default_reset_vector = Input(UInt(raw.W))
    val jtag = chiselTypeOf(uncore.io.debug.systemjtag.get)
  })
  val dft = IO(Input(new DftWires))
  implicitReset := io.reset

  io.ddr <> ddrSys.io.ddr
  noc.io.chip := io.chip
  noc.dft := dft
  noc.clock := io.noc_clock

  uncore.io.ext.dma <> io.dma
  io.cfg <> uncore.io.ext.cfg
  uncore.io.ext.timerTick := io.rtc_clock
  uncore.io.ext.intr := io.ext_intr
  uncore.io.chip := io.chip
  uncore.io.debug.systemjtag.foreach(_ <> io.jtag)
  uncore.clock := io.soc_clock
  uncore.dft := dft
  uncore.io.debug.dmactiveAck := uncore.io.debug.dmactive
  uncore.io.debug.clock := DontCare
  uncore.io.debug.reset := DontCare
  io.ndreset := uncore.io.debug.ndreset

  private val nanhuNode = noc.io.cluster.groupBy(_.node.attr)("nanhu").head.node
  private val nanhuClusterDef = Definition(new CpuCluster(nanhuNode))
  private val cpuNum = noc.io.cluster.map(_.node.cpuNum).sum

  val core = if(p(RemoveCoreKey)) Some(IO(Vec(cpuNum, new CoreBlockTestIO(nanhuClusterDef.coreIoParams)))) else None

  for((icn, i) <- noc.io.cluster.zipWithIndex) {
    val clusterId = icn.node.clusterId
    val cc = Instance(nanhuClusterDef)
    cc.icn.async <> icn
    cc.icn.osc_clock := io.cluster_clocks(i)
    cc.icn.dft := dft
    for(i <- 0 until icn.node.cpuNum) {
      val cid = clusterId + i
      cc.icn.misc.msip(i) := uncore.io.cpu.msip(cid)
      cc.icn.misc.mtip(i) := uncore.io.cpu.mtip(cid)
      cc.icn.misc.meip(i) := uncore.io.cpu.meip(cid)
      cc.icn.misc.seip(i) := uncore.io.cpu.seip(cid)
      cc.icn.misc.dbip(i) := uncore.io.cpu.dbip(cid)
      cc.icn.misc.mhartid(i) := Cat(io.chip, cid.U((clusterIdBits - nodeAidBits).W))
      uncore.io.resetCtrl.hartIsInReset(cid) := cc.icn.misc.resetState(i)
      cc.icn.misc.resetVector(i) := io.default_reset_vector
      if(cid == 0) {
        cc.icn.misc.resetEnable(i) := true.B
      } else {
        cc.icn.misc.resetEnable(i) := false.B
      }
      if(p(RemoveCoreKey)) core.get(cid) <> cc.core.get(i)
    }
  }
}
