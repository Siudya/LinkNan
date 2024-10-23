package lntest.top
import SimpleL2.Configs.L2ParamKey
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import linknan.cluster.{CoreBlockTestIO, CoreBlockTestIOParams}
import org.chipsalliance.cde.config.Parameters
import xiangshan.XSCoreParamsKey
import xs.utils.tl.{TLNanhuBusField, TLNanhuBusKey}
import zhujiang.ZJParametersKey

class SimCoreHubExtIO(cioP:TLBundleParameters, dcP:TLBundleParameters, icP:TLBundleParameters)(implicit p:Parameters) extends Bundle {
  val clock = Output(Clock())
  val reset = Output(AsyncReset())
  val cio = Flipped(new TLBundle(cioP))
  val dcache = Flipped(new TLBundle(dcP))
  val icache = Flipped(new TLBundle(icP))
  val mhartid = Output(UInt(p(ZJParametersKey).clusterIdBits.W))
}

class SimCoreHub(params:CoreBlockTestIOParams)(implicit p: Parameters) extends LazyModule {
  private val icacheImplParams = p(XSCoreParamsKey).icacheParameters
  private val icacheDplmcParams = TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "icache",
      sourceId = IdRange(0, icacheImplParams.nMissEntries + icacheImplParams.nReleaseEntries + icacheImplParams.nPrefetchEntries),
    )),
    requestFields = Seq(),
    echoFields = Seq(),
  )
  private val icacheNode = TLClientNode(Seq(icacheDplmcParams))

  private val dcacheImplParams = p(XSCoreParamsKey).dcacheParametersOpt.get
  private val dcacheDplmcParames = TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "dcache",
      sourceId = IdRange(0, dcacheImplParams.nMissEntries + dcacheImplParams.nReleaseEntries + 1),
      supportsProbe = TransferSizes(dcacheImplParams.blockBytes)
    )),
    requestFields = Seq(new TLNanhuBusField)
  )
  private val dcacheNode = TLClientNode(Seq(dcacheDplmcParames))

  private val l2Implparam = p(L2ParamKey)
  private val l2DplmcParams = TLSlavePortParameters.v1(
    managers = Seq(
      TLSlaveParameters.v1(
        address = Seq(l2Implparam.addressSet),
        regionType = RegionType.CACHED,
        supportsAcquireT = TransferSizes(l2Implparam.blockBytes, l2Implparam.blockBytes),
        supportsAcquireB = TransferSizes(l2Implparam.blockBytes, l2Implparam.blockBytes),
        supportsArithmetic = TransferSizes(1, l2Implparam.beatBytes),
        supportsGet = TransferSizes(1, l2Implparam.beatBytes),
        supportsLogical = TransferSizes(1, l2Implparam.beatBytes),
        supportsPutFull = TransferSizes(1, l2Implparam.beatBytes),
        supportsPutPartial = TransferSizes(1, l2Implparam.beatBytes)
      )
    ),
    beatBytes = 32,
    minLatency = 2,
    responseFields = Nil,
    requestKeys = Seq(TLNanhuBusKey),
    endSinkId = 1 << params.l2Params.sinkBits
  )
  val l2Node = TLManagerNode(Seq(l2DplmcParams))

  private val coreXBar = LazyModule(new TLXbar)
  coreXBar.node :*= icacheNode
  coreXBar.node :*= dcacheNode
  l2Node :*= coreXBar.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private val dcache = dcacheNode.out.head._1
    private val icache = icacheNode.out.head._1
    private val l2 = l2Node.in.head._1
    val io = IO(new Bundle {
      val noc = Flipped(new CoreBlockTestIO(params))
      val ext = new SimCoreHubExtIO(params.ioParams, dcache.params, icache.params)
    })

    io.ext.clock := io.noc.clock
    io.ext.reset := io.noc.reset
    io.noc.cio <> io.ext.cio
    io.noc.l2 <> l2
    io.ext.mhartid := io.noc.mhartid
    io.ext.dcache <> dcache
    io.ext.icache <> icache
  }
}
