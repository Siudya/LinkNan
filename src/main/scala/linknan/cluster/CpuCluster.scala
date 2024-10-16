package linknan.cluster

import SimpleL2.Configs.L2ParamKey
import SimpleL2.chi.CHIBundleParameters
import chisel3._
import chisel3.experimental.hierarchy.{instantiable, public}
import darecreek.exu.vfu.{VFuParameters, VFuParamsKey}
import freechips.rocketchip.diplomacy.{LazyModule, MonitorsEnabled}
import org.chipsalliance.cde.config.Parameters
import xiangshan.XSCoreParamsKey
import xijiang.{Node, NodeType}
import xs.utils.tl.{TLUserKey, TLUserParams}
import xs.utils.{ClockManagerWrapper, ResetGen}
import zhujiang.ZJModule
import zhujiang.device.cluster.ClusterInterconnectComplex
import zhujiang.device.cluster.interconnect.ClusterDeviceBundle

class CpuClusterInner(node:Node)(implicit p:Parameters) extends ZJModule {
  private val dcacheParams = p(XSCoreParamsKey).dcacheParametersOpt.get
  private val l2Params = p(L2ParamKey)

  val io = IO(new Bundle {
    val icn = new ClusterDeviceBundle(node)
    val pllCfg = Output(Vec(8, UInt(32.W)))
    val pllLock = Input(Bool())
  })

  private val ccn = LazyModule(new CpuSubsystem(node.copy(nodeType = NodeType.RF))(p.alterPartial({
    case TLUserKey => TLUserParams(aliasBits = dcacheParams.aliasBitsOpt.getOrElse(0))
    case L2ParamKey => l2Params.copy(
      nrClients = node.cpuNum,
      chiBundleParams = Some(CHIBundleParameters(
        nodeIdBits = niw,
        addressBits = raw
      ))
    )
    case VFuParamsKey => VFuParameters()
    case MonitorsEnabled => false
  })))
  private val cc = Module(ccn.module)
  private val hub = Module(new ClusterInterconnectComplex(node, cc.io.cio.params))

  hub.io.l2cache <> cc.io.l2cache
  hub.io.cio <> cc.io.cio
  hub.io.pllLock := io.pllLock
  io.icn <> hub.io.icn
  io.pllCfg := hub.io.pllCfg

  cc.io.cpu <> hub.io.cpu
  cc.io.clock := clock
  cc.io.reset := reset
  cc.dft := hub.io.dft
}

@instantiable
class CpuCluster(node:Node)(implicit p:Parameters) extends RawModule {
  @public val icn = IO(new ClusterDeviceBundle(node))

  private val pll = Module(new ClockManagerWrapper)
  private val resetGen = withClockAndReset(pll.io.cpu_clock, icn.async.resetRx) { Module(new ResetGen) }
  private val cluster = withClockAndReset(pll.io.cpu_clock, resetGen.o_reset) { Module(new CpuClusterInner(node)) }
  icn <> cluster.io.icn
  resetGen.dft := icn.dft.reset
  pll.io.cfg := cluster.io.pllCfg
  cluster.io.pllLock := pll.io.lock
  pll.io.in_clock := icn.osc_clock
}