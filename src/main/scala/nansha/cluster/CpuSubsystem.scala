package nansha.cluster

import SimpleL2.SimpleL2CacheDecoupled
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import utils.IntBuffer
import xiangshan.{BusErrorUnitInfo, XSCore, XSCoreParamsKey}
import xijiang.Node
import xijiang.router.base.DeviceIcnBundle
import xs.utils.{RegNextN, ResetGen}
import zhujiang.ZJParametersKey
import zhujiang.chi._
import zhujiang.device.cluster.interconnect.{ClusterMiscWires, DftWires}
import zhujiang.tilelink.{TLULBundle, TilelinkParams}

class CpuSubsystem(l2Node:Node)(implicit p:Parameters) extends LazyModule with BindingScope {
  private val coreNum = l2Node.cpuNum
  private val coreParams = p(XSCoreParamsKey)
  private val coreSeq = Seq.fill(coreNum)(LazyModule(new XSCore))
  private val coreXbarSeq = Seq.fill(coreNum)(LazyModule(new TLXbar))

  private val clintIntBufSeq = Seq.fill(coreNum)(LazyModule(new IntBuffer))
  private val plicIntBufSeq = Seq.fill(coreNum)(LazyModule(new IntBuffer))
  private val debugIntBufSeq = Seq.fill(coreNum)(LazyModule(new IntBuffer))
  private val clintIntSrcSeq = Seq.fill(coreNum)(IntSourceNode(IntSourcePortSimple(2, 1)))
  private val plicIntSrcSeq = Seq.fill(coreNum)(IntSourceNode(IntSourcePortSimple(1, 2)))
  private val debugIntSrcSeq = Seq.fill(coreNum)(IntSourceNode(IntSourcePortSimple(1, 1)))

  private val l2cache = LazyModule(new SimpleL2CacheDecoupled)
  private val l2xbar = LazyModule(new TLXbar)
  private val l2binder = LazyModule(new BankBinder(64 * (coreParams.L2NBanks - 1)))
  private val l2EccIntSink = IntSinkNode(IntSinkPortSimple(1, 1))

  private val clusterCioXbar = LazyModule(new TLXbar)

  private val mmioSlvParams = TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(0L, (0x1L << p(ZJParametersKey).requestAddrBits) - 1)),
      supportsGet = TransferSizes(1, 8),
      supportsPutFull = TransferSizes(1, 8),
      supportsPutPartial = TransferSizes(1, 8),
    )),
    beatBytes = 8
  )

  private val cioNode = TLManagerNode(Seq(mmioSlvParams))

  for(i <- 0 until coreNum) {
    coreXbarSeq(i).node :*= TLBuffer.chainNode(1, Some(s"core_${i}_l1d_buffer")) :*= coreSeq(i).exuBlock.memoryBlock.dcache.clientNode
    coreXbarSeq(i).node :*= TLBuffer.chainNode(1, Some(s"core_${i}_ptw_buffer")) :*= coreSeq(i).ptw_to_l2_buffer.node
    coreXbarSeq(i).node :*= TLBuffer.chainNode(1, Some(s"core_${i}_l1i_buffer")) :*= coreSeq(i).frontend.icache.clientNode
    l2xbar.node :*= coreXbarSeq(i).node
    coreSeq(i).clint_int_sink :*= clintIntBufSeq(i).node :*= clintIntSrcSeq(i)
    coreSeq(i).plic_int_sink :*= plicIntBufSeq(i).node :*= plicIntSrcSeq(i)
    coreSeq(i).debug_int_sink :*= debugIntBufSeq(i).node :*= debugIntSrcSeq(i)
    coreSeq(i).suggestName(s"core_$i")
    clusterCioXbar.node :*= TLBuffer.chainNode(1, Some(s"core_${i}_uncache_buffer")) :*= coreSeq(i).uncacheBuffer.node
  }
  l2binder.node :*= TLBuffer.chainNode(1, Some(s"l2_binder_buffer")) :*= l2xbar.node
  for(i <- 0 until coreParams.L2NBanks) l2cache.sinkNodes(i) :*= TLBuffer.chainNode(2, Some(s"l2_in_buffer")) :*= l2binder.node
  l2EccIntSink :=* l2cache.eccIntNode
  cioNode :*= TLBuffer.chainNode(1, Some(s"cluster_uncache_buffer")) :*= clusterCioXbar.node

  lazy val module = new Impl
  class Impl extends LazyRawModuleImp(this) {
    private val cioBundle = cioNode.in.head._1
    private val cioParams = TilelinkParams(
      addrBits = cioBundle.a.bits.address.getWidth,
      dataBits = cioBundle.a.bits.data.getWidth,
      sourceBits = cioBundle.a.bits.source.getWidth,
    )
    val io = IO(new Bundle {
      val cpu = new ClusterMiscWires(l2Node)
      val l2cache = new DeviceIcnBundle(l2Node)
      val cio = new TLULBundle(cioParams)
      val icacheErr = Output(Vec(coreNum, new BusErrorUnitInfo))
      val dcacheErr = Output(Vec(coreNum, new BusErrorUnitInfo))
      val l2cacheErr = Output(new BusErrorUnitInfo)
      val clock = Input(Clock())
      val reset = Input(AsyncReset())
    })
    val dft = IO(Input(new DftWires))

    private val resetSyncAll = withClockAndReset(io.clock, io.reset) { ResetGen(dft = Some(dft.reset))}
    private val resetSyncMisc = withClockAndReset(io.clock, resetSyncAll) { ResetGen(dft = Some(dft.reset))}
    childClock := io.clock
    childReset := resetSyncMisc

    for(i <- 0 until coreNum) {
      val core = coreSeq(i).module
      core.io.hartId := io.cpu.mhartid(i)
      core.io.reset_vector := io.cpu.resetVector(i)
      io.cpu.halt(i) := withClock(io.clock) { RegNextN(core.io.cpu_halt, 3) }
      core.io.perfEvents := DontCare
      io.icacheErr(i) := core.io.l1iErr
      io.dcacheErr(i) := core.io.l1dErr
      core.io.dfx_reset := dft.reset
      core.dft.foreach(_ := dft.func)
      clintIntSrcSeq(i).out.head._1(0) := io.cpu.msip(i)
      clintIntSrcSeq(i).out.head._1(1) := io.cpu.mtip(i)
      plicIntSrcSeq(i).out.head._1(0) := io.cpu.meip(i)
      plicIntSrcSeq(i).out.last._1(0) := io.cpu.seip(i)
      debugIntSrcSeq(i).out.head._1(0) := io.cpu.dbip(i)
      val coreRst = Wire(Bool())
      coreRst := resetSyncAll.asBool || io.cpu.resetEnable(i)
      withClockAndReset(io.clock, coreRst) {
        core.reset := ResetGen(dft = Some(dft.reset))
        val rstStateReg = RegInit("b111".U(3.W))
        rstStateReg.suggestName(s"core_${i}_reset_state")
        rstStateReg := Cat(0.U(1.W), rstStateReg(2, 1))
        io.cpu.resetState(i) := rstStateReg(0)
      }
    }

    io.cpu.beu := DontCare
    io.l2cacheErr.ecc_error.valid := l2EccIntSink.in.head._1(0)
    io.l2cacheErr.ecc_error.bits := DontCare

    io.cio.a.valid := cioBundle.a.valid
    cioBundle.a.ready := io.cio.a.ready

    cioBundle.d.valid := io.cio.d.valid
    io.cio.d.ready := cioBundle.d.ready

    private val l2 = l2cache.module
    private val txreq = Wire(new ReqFlit)
    private val txrsp = Wire(new RespFlit)
    private val txdat = Wire(new DataFlit)
    private val rxrsp = Wire(new RespFlit)
    private val rxdat = Wire(new DataFlit)
    private val rxsnp = Wire(new SnoopFlit)

    io.l2cache.tx.req.get.bits := txreq.asTypeOf(io.l2cache.tx.req.get.bits)
    io.l2cache.tx.resp.get.bits := txrsp.asTypeOf(io.l2cache.tx.resp.get.bits)
    io.l2cache.tx.data.get.bits := txdat.asTypeOf(io.l2cache.tx.data.get.bits)
    rxrsp := io.l2cache.rx.resp.get.bits.asTypeOf(rxrsp)
    rxdat := io.l2cache.rx.data.get.bits.asTypeOf(rxdat)
    rxsnp := io.l2cache.rx.snoop.get.bits.asTypeOf(rxsnp)

    io.l2cache.tx.req.get.valid := l2.io.chi.txreq.valid
    l2.io.chi.txreq.ready := io.l2cache.tx.req.get.ready

    io.l2cache.tx.resp.get.valid := l2.io.chi.txrsp.valid
    l2.io.chi.txrsp.ready := io.l2cache.tx.resp.get.ready

    io.l2cache.tx.data.get.valid := l2.io.chi.txdat.valid
    l2.io.chi.txdat.ready := io.l2cache.tx.data.get.ready

    l2.io.chi.rxrsp.valid := io.l2cache.rx.resp.get.valid
    io.l2cache.rx.resp.get.ready := l2.io.chi.rxrsp.ready

    l2.io.chi.rxdat.valid := io.l2cache.rx.data.get.valid
    io.l2cache.rx.data.get.ready := l2.io.chi.rxdat.ready

    l2.io.chi.rxsnp.valid := io.l2cache.rx.snoop.get.valid
    io.l2cache.rx.snoop.get.ready := l2.io.chi.rxsnp.ready

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
    private val mmio = (1L << 47).U
    io.cio.a.bits.address := cioBundle.a.bits.address | mmio
    connByName(io.cio.a.bits, cioBundle.a.bits)
    connByName(cioBundle.d.bits, io.cio.d.bits)
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
  }
}
