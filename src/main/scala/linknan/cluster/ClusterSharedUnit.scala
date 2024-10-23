package linknan.cluster

import chisel3._
import chisel3.util._
import SimpleL2.Configs.L2ParamKey
import SimpleL2.SimpleL2CacheDecoupled
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import xiangshan.HasXSParameter
import xijiang.Node
import xs.utils.ResetGen
import xs.utils.tl.TLNanhuBusField
import zhujiang.chi.{DataFlit, ReqFlit, RespFlit, SnoopFlit}
import zhujiang.device.cluster.ClusterInterconnectComplex
import zhujiang.device.cluster.interconnect.ClusterDeviceBundle
import zhujiang.tilelink.TilelinkParams

class ClusterSharedUnit(cioEdge: TLEdgeIn, l2EdgeIn: TLEdgeIn, node:Node)(implicit p:Parameters) extends LazyModule with BindingScope with HasXSParameter {

  private val l2cache = LazyModule(new SimpleL2CacheDecoupled(/* tlEdgeInOpt = Some(l2EdgeIn)) */))
  private val l2xbar = LazyModule(new TLXbar)
  private val l2binder = LazyModule(new BankBinder(64 * (coreParams.L2NBanks - 1)))
  private val l2EccIntSink = IntSinkNode(IntSinkPortSimple(1, 1))
  private val cioBundle = cioEdge.bundle
  private val l2Bundle = l2EdgeIn.bundle
  private val l2param = p(L2ParamKey)
  private val cachePortParams = TLMasterPortParameters.v2(
    masters = Seq(
      TLMasterParameters.v1(
        name = name,
        sourceId = IdRange(0, 1 << l2EdgeIn.bundle.sourceBits),
        supportsProbe = TransferSizes(l2param.blockBytes)
      )
    ),
    channelBytes = TLChannelBeatBytes(l2param.beatBytes),
    minLatency = 1,
    echoFields = Nil,
    requestFields = Seq(new TLNanhuBusField),
    responseKeys = Nil
  )
  private val cachePortNode = Seq.fill(node.cpuNum)(TLClientNode(Seq(cachePortParams)))
  for(i <- 0 until node.cpuNum) {
    l2xbar.node :*= TLBuffer.chainNode(1, Some(s"core_${i}_cache_buffer")) :*= cachePortNode(i)
  }
  l2binder.node :*= l2xbar.node
  for(i <- 0 until l2param.nrSlice) l2cache.sinkNodes(i) :*= TLBuffer.chainNode(2, Some(s"l2_in_buffer")) :*= l2binder.node
  l2EccIntSink :=* l2cache.eccIntNode

  lazy val module = new Impl
  class Impl extends LazyRawModuleImp(this) {
    val io = IO(new Bundle{
      val clock = Input(Clock())
      val reset = Input(AsyncReset())
      val core = Vec(node.cpuNum, Flipped(new CoreWrapperIO(cioBundle, l2Bundle)))
      val icn = new ClusterDeviceBundle(node)
      val pllCfg = Output(Vec(8, UInt(32.W)))
      val pllLock = Input(Bool())
    })
    dontTouch(io)

    private val cioParams = TilelinkParams(cioBundle.addressBits, cioBundle.sourceBits, cioBundle.sinkBits, cioBundle.dataBits)
    private val l2 = l2cache.module
    private val resetSyncAll = withClockAndReset(io.clock, io.reset) { ResetGen(dft = Some(io.icn.dft.reset))}
    private val resetSyncMisc = withClockAndReset(io.clock, resetSyncAll) { ResetGen(dft = Some(io.icn.dft.reset))}
    childClock := io.clock
    childReset := resetSyncMisc
    private val hub = withClockAndReset(childClock, childReset) {Module(new ClusterInterconnectComplex(node, cioParams))}
    io.icn <> hub.io.icn
    io.pllCfg := hub.io.pllCfg
    hub.io.pllLock := io.pllLock
    hub.io.cpu.beu := DontCare

    private val txreq = Wire(new ReqFlit)
    private val txrsp = Wire(new RespFlit)
    private val txdat = Wire(new DataFlit)
    private val rxrsp = Wire(new RespFlit)
    private val rxdat = Wire(new DataFlit)
    private val rxsnp = Wire(new SnoopFlit)

    hub.io.l2cache.rx.req.get.bits := txreq.asTypeOf(hub.io.l2cache.rx.req.get.bits)
    hub.io.l2cache.rx.resp.get.bits := txrsp.asTypeOf(hub.io.l2cache.rx.resp.get.bits)
    hub.io.l2cache.rx.data.get.bits := txdat.asTypeOf(hub.io.l2cache.rx.data.get.bits)
    rxrsp := hub.io.l2cache.tx.resp.get.bits.asTypeOf(rxrsp)
    rxdat := hub.io.l2cache.tx.data.get.bits.asTypeOf(rxdat)
    rxsnp := hub.io.l2cache.tx.snoop.get.bits.asTypeOf(rxsnp)

    hub.io.l2cache.rx.req.get.valid := l2.io.chi.txreq.valid
    l2.io.chi.txreq.ready := hub.io.l2cache.rx.req.get.ready

    hub.io.l2cache.rx.resp.get.valid := l2.io.chi.txrsp.valid
    l2.io.chi.txrsp.ready := hub.io.l2cache.rx.resp.get.ready

    hub.io.l2cache.rx.data.get.valid := l2.io.chi.txdat.valid
    l2.io.chi.txdat.ready := hub.io.l2cache.rx.data.get.ready

    l2.io.chi.rxrsp.valid := hub.io.l2cache.tx.resp.get.valid
    hub.io.l2cache.tx.resp.get.ready := l2.io.chi.rxrsp.ready

    l2.io.chi.rxdat.valid := hub.io.l2cache.tx.data.get.valid
    hub.io.l2cache.tx.data.get.ready := l2.io.chi.rxdat.ready

    l2.io.chi.rxsnp.valid := hub.io.l2cache.tx.snoop.get.valid
    hub.io.l2cache.tx.snoop.get.ready := l2.io.chi.rxsnp.ready

    l2.io.chi_tx_rxsactive := true.B
    l2.io.chi_tx_linkactiveack := true.B
    l2.io.chi_rx_linkactivereq := true.B
    l2.io.nodeID := DontCare

    private def connByName(sink:Bundle, source:Bundle):Unit = {
      sink := DontCare
      val recvMap = sink.elements.map(e => (e._1.toLowerCase, e._2))
      val sendMap = source.elements.map(e => (e._1.toLowerCase, e._2))
      for((name, data) <- recvMap) {
        if(sendMap.contains(name)) data := sendMap(name).asTypeOf(data)
      }
    }
    connByName(txreq, l2.io.chi.txreq.bits)
    connByName(txrsp, l2.io.chi.txrsp.bits)
    connByName(txdat, l2.io.chi.txdat.bits)
    connByName(l2.io.chi.rxrsp.bits, rxrsp)
    connByName(l2.io.chi.rxdat.bits, rxdat)
    connByName(l2.io.chi.rxsnp.bits, rxsnp)
    txreq.LPID := l2.io.chi.txreq.bits.lpID
    txreq.SnoopMe := l2.io.chi.txreq.bits.snoopMe
    txdat.FwdState := l2.io.chi.txdat.bits.fwdState
    l2.io.chi.rxdat.bits.fwdState := rxdat.FwdState

    private val mmio = (1L << 47).U
    for(i <- 0 until node.cpuNum) {
      val core = io.core(i)
      cachePortNode(i).out.head._1 <> core.l2

      hub.io.cio(i).a.valid := core.cio.a.valid
      core.cio.a.ready := hub.io.cio(i).a.ready
      connByName(hub.io.cio(i).a.bits, core.cio.a.bits)
      hub.io.cio(i).a.bits.address := core.cio.a.bits.address | mmio

      core.cio.d.valid := hub.io.cio(i).d.valid
      hub.io.cio(i).d.ready := core.cio.d.ready
      connByName(core.cio.d.bits, hub.io.cio(i).d.bits)

      core.clock := childClock
      core.reset := resetSyncAll
      core.mhartid := hub.io.cpu.mhartid(i)
      core.reset_vector := hub.io.cpu.resetVector(i)
      hub.io.cpu.halt(i) := core.halt
      core.msip := hub.io.cpu.msip(i)
      core.mtip := hub.io.cpu.mtip(i)
      core.meip := hub.io.cpu.meip(i)
      core.seip := hub.io.cpu.seip(i)
      core.dbip := hub.io.cpu.dbip(i)
      hub.io.cpu.resetState(i) := core.reset_state
      core.dft := hub.io.dft
    }
  }
}
