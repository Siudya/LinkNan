package linknan.cluster

import SimpleL2.Configs.L2ParamKey
import SimpleL2.chi.CHIBundleParameters
import chisel3._
import chisel3.experimental.hierarchy.{Definition, Instance, instantiable, public}
import darecreek.exu.vfu.{VFuParameters, VFuParamsKey}
import freechips.rocketchip.diplomacy.{LazyModule, MonitorsEnabled}
import linknan.generator.RemoveCoreKey
import org.chipsalliance.cde.config.Parameters
import xiangshan.XSCoreParamsKey
import xijiang.Node
import xs.utils.tl.{TLUserKey, TLUserParams}
import xs.utils.{ClockManagerWrapper, ResetGen}
import zhujiang.ZJRawModule
import zhujiang.device.cluster.interconnect.ClusterDeviceBundle

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

  @public val icn = IO(new ClusterDeviceBundle(node))
  @public val core = if(removeCore) Some(IO(Vec(node.cpuNum, Flipped(new CoreWrapperIO(cioParams, cl2Params))))) else None

  private val pll = Module(new ClockManagerWrapper)
  private val resetSync = withClockAndReset(pll.io.cpu_clock, icn.async.resetRx) { ResetGen(dft = Some(icn.dft.reset)) }

  icn <> _csu.io.icn

  _csu.io.pllLock := pll.io.lock
  pll.io.cfg := _csu.io.pllCfg
  pll.io.in_clock := icn.osc_clock

  _csu.io.clock := pll.io.cpu_clock
  _csu.io.reset := resetSync

  if(removeCore) {
    for(i <- 0 until node.cpuNum) _csu.io.core(i) <> core.get(i)
  } else {
    for(i <- 0 until node.cpuNum) _csu.io.core(i) <> coreSeq.get(i).io
  }
}