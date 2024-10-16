package linknan.cluster

import chisel3._
import chisel3.util._
import SimpleL2.Configs.L2ParamKey
import chisel3.experimental.hierarchy.{instantiable, public}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts.{IntSourceNode, IntSourcePortSimple}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import utils.IntBuffer
import xiangshan.{BusErrorUnitInfo, HasXSParameter, XSCore}
import xs.utils.{RegNextN, ResetGen}
import xs.utils.tl.TLNanhuBusKey
import zhujiang.ZJParametersKey
import zhujiang.device.cluster.interconnect.DftWires

class CoreWrapperIO(ioParams:TLBundleParameters, l2Params: TLBundleParameters)(implicit p:Parameters) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(AsyncReset())
  val cio = new TLBundle(ioParams)
  val l2 = new TLBundle(l2Params)
  val mhartid = Input(UInt(p(ZJParametersKey).clusterIdBits.W))
  val reset_vector = Input(UInt(p(ZJParametersKey).requestAddrBits.W))
  val halt = Output(Bool())
  val icacheErr = Output(new BusErrorUnitInfo)
  val dcacheErr = Output(new BusErrorUnitInfo)
  val msip = Input(Bool())
  val mtip = Input(Bool())
  val meip = Input(Bool())
  val seip = Input(Bool())
  val dbip = Input(Bool())
  val reset_state = Output(Bool())
  val dft = Input(new DftWires)
}

class CoreWrapper(implicit p:Parameters) extends LazyModule with BindingScope with HasXSParameter {
  private val core = LazyModule(new XSCore)
  private val coreXBar = LazyModule(new TLXbar)
  private val clintIntBuf = LazyModule(new IntBuffer)
  private val plicIntBuf = LazyModule(new IntBuffer)
  private val debugIntBuf = LazyModule(new IntBuffer)
  private val clintIntSrc = IntSourceNode(IntSourcePortSimple(2, 1))
  private val plicIntSrc = IntSourceNode(IntSourcePortSimple(1, 2))
  private val debugIntSrc = IntSourceNode(IntSourcePortSimple(1, 1))

  private val mmioSlvParams = TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(0L, (0x1L << p(ZJParametersKey).requestAddrBits) - 1)),
      supportsGet = TransferSizes(1, 8),
      supportsPutFull = TransferSizes(1, 8),
      supportsPutPartial = TransferSizes(1, 8),
    )),
    beatBytes = 8
  )

  private val l2param = p(L2ParamKey)
  private val l2NodeParameters = TLSlavePortParameters.v1(
    managers = Seq(
      TLSlaveParameters.v1(
        address = Seq(l2param.addressSet),
        regionType = RegionType.CACHED,
        supportsAcquireT = TransferSizes(l2param.blockBytes, l2param.blockBytes),
        supportsAcquireB = TransferSizes(l2param.blockBytes, l2param.blockBytes),
        supportsArithmetic = TransferSizes(1, l2param.beatBytes),
        supportsGet = TransferSizes(1, l2param.beatBytes),
        supportsLogical = TransferSizes(1, l2param.beatBytes),
        supportsPutFull = TransferSizes(1, l2param.beatBytes),
        supportsPutPartial = TransferSizes(1, l2param.beatBytes)
      )
    ),
    beatBytes = 32,
    minLatency = 2,
    responseFields = Nil,
    requestKeys = Seq(TLNanhuBusKey),
    endSinkId = 256 * (1 << log2Ceil(coreParams.L2NBanks))
  )

  val cioNode = TLManagerNode(Seq(mmioSlvParams))
  val l2Node = TLManagerNode(Seq(l2NodeParameters))

  coreXBar.node :*= TLBuffer.chainNode(1, Some(s"l1d_buffer")) :*= core.exuBlock.memoryBlock.dcache.clientNode
  coreXBar.node :*= TLBuffer.chainNode(1, Some(s"ptw_buffer")) :*= core.ptw_to_l2_buffer.node
  coreXBar.node :*= TLBuffer.chainNode(1, Some(s"l1i_buffer")) :*= core.frontend.icache.clientNode
  l2Node :*= coreXBar.node
  core.clint_int_sink :*= clintIntBuf.node :*= clintIntSrc
  core.plic_int_sink :*= plicIntBuf.node :*= plicIntSrc
  core.debug_int_sink :*= debugIntBuf.node :*= debugIntSrc
  cioNode :*= TLBuffer.chainNode(1, Some(s"uncache_buffer")) :*= core.uncacheBuffer.node

  lazy val module = new Impl
  @instantiable
  class Impl extends LazyRawModuleImp(this) {
    private val ioParams = cioNode.in.head._2.bundle
    private val l2Params = l2Node.in.head._2.bundle
    @public val io = IO(new CoreWrapperIO(ioParams, l2Params))
    dontTouch(io)
    childClock := io.clock
    childReset := withClockAndReset(io.clock, io.reset){ ResetGen(dft = Some(io.dft.reset)) }
    io.cio <> cioNode.in.head._1
    io.l2 <> l2Node.in.head._1

    core.module.io.hartId := io.mhartid
    core.module.io.reset_vector := io.reset_vector
    io.halt := withClock(childClock) { RegNextN(core.module.io.cpu_halt, 3) }
    core.module.io.perfEvents := DontCare
    io.icacheErr := core.module.io.l1iErr
    io.dcacheErr := core.module.io.l1dErr
    core.module.io.dfx_reset := io.dft.reset
    core.module.dft.foreach(_ := io.dft.func)
    clintIntSrc.out.head._1(0) := io.msip
    clintIntSrc.out.head._1(1) := io.mtip
    plicIntSrc.out.head._1(0) := io.meip
    plicIntSrc.out.last._1(0) := io.seip
    debugIntSrc.out.head._1(0) := io.dbip
    io.reset_state := withClockAndReset(childClock, childReset) {
      val rstStateReg = RegInit("b111".U(3.W))
      rstStateReg := Cat(0.U(1.W), rstStateReg(2, 1))
      rstStateReg(0)
    }
  }
}
