package linknan.cluster

import SimpleL2.Configs.L2ParamKey
import SimpleL2.chi.CHIBundleParameters
import chisel3._
import chisel3.experimental.hierarchy.core.IsLookupable
import chisel3.experimental.hierarchy.{Definition, Instance, instantiable, public}
import darecreek.exu.vfu.{VFuParameters, VFuParamsKey}
import freechips.rocketchip.diplomacy.{LazyModule, MonitorsEnabled}
import freechips.rocketchip.tilelink.{TLBundle, TLBundleParameters}
import linknan.generator.RemoveCoreKey
import org.chipsalliance.cde.config.Parameters
import xiangshan.XSCoreParamsKey
import xijiang.Node
import xs.utils.tl.{TLUserKey, TLUserParams}
import xs.utils.{ClockManagerWrapper, ResetGen}
import zhujiang.{ZJParametersKey, ZJRawModule}
import zhujiang.device.cluster.interconnect.ClusterDeviceBundle

class CoreBlockTestIO(params:CoreBlockTestIOParams)(implicit p:Parameters) extends Bundle {
  val clock = Output(Clock())
  val reset = Output(AsyncReset())
  val cio = Flipped(new TLBundle(params.ioParams))
  val l2 = Flipped(new TLBundle(params.l2Params))
  val mhartid = Output(UInt(p(ZJParametersKey).clusterIdBits.W))
}

case class CoreBlockTestIOParams(ioParams:TLBundleParameters, l2Params: TLBundleParameters) extends IsLookupable

@instantiable
class CpuCluster(node:Node)(implicit p:Parameters) extends ZJRawModule {
  private val removeCore = p(RemoveCoreKey)
  private val dcacheParams = p(XSCoreParamsKey).dcacheParametersOpt.get
  private val l2Params = p(L2ParamKey)

  private val coreGen = LazyModule(new CoreWrapper()(p.alterPartial({
    case MonitorsEnabled => false
    case TLUserKey => TLUserParams(aliasBits = dcacheParams.aliasBitsOpt.getOrElse(0))
    case VFuParamsKey => VFuParameters()
  })))
  private val coreDef = if(!removeCore) Some(Definition(coreGen.module)) else None
  private val coreSeq = if(!removeCore) Some(Seq.fill(node.cpuNum)(Instance(coreDef.get))) else None
  coreSeq.foreach(_.zipWithIndex.foreach({case(c, i) => c.suggestName(s"core_$i")}))
  private val cioParams = coreGen.cioNode.edges.in.head.bundle
  private val cl2Params = coreGen.l2Node.edges.in.head.bundle

  private val csu = LazyModule(new ClusterSharedUnit(cioParams, cl2Params, node)(p.alterPartial({
    case MonitorsEnabled => false
    case TLUserKey => TLUserParams(aliasBits = dcacheParams.aliasBitsOpt.getOrElse(0))
    case L2ParamKey => l2Params.copy(
      nrClients = node.cpuNum,
      chiBundleParams = Some(CHIBundleParameters(
        nodeIdBits = niw,
        addressBits = raw
      ))
    )
  })))
  private val _csu = Module(csu.module)

  @public val coreIoParams = CoreBlockTestIOParams(cioParams, cl2Params)
  @public val icn = IO(new ClusterDeviceBundle(node))
  @public val core = if(removeCore) Some(IO(Vec(node.cpuNum, new CoreBlockTestIO(coreIoParams)))) else None

  private val pll = Module(new ClockManagerWrapper)
  private val resetSync = withClockAndReset(pll.io.cpu_clock, icn.async.resetRx) { ResetGen(dft = Some(icn.dft.reset)) }

  icn <> _csu.io.icn

  _csu.io.pllLock := pll.io.lock
  pll.io.cfg := _csu.io.pllCfg
  pll.io.in_clock := icn.osc_clock

  _csu.io.clock := pll.io.cpu_clock
  _csu.io.reset := resetSync

  if(removeCore) {
    for(i <- 0 until node.cpuNum) {
      core.get(i).l2 <> _csu.io.core(i).l2
      core.get(i).cio <> _csu.io.core(i).cio
      core.get(i).reset := _csu.io.core(i).reset
      core.get(i).clock <> _csu.io.core(i).clock
      core.get(i).mhartid <> _csu.io.core(i).mhartid
      _csu.io.core(i).halt := false.B
      _csu.io.core(i).icacheErr := DontCare
      _csu.io.core(i).dcacheErr := DontCare
      _csu.io.core(i).reset_state := false.B
    }
  } else {
    for(i <- 0 until node.cpuNum) _csu.io.core(i) <> coreSeq.get(i).io
  }
}