package lntest.top

import chisel3.Module
import freechips.rocketchip.amba.axi4.{AXI4MasterNode, AXI4MasterParameters, AXI4MasterPortParameters, AXI4SlaveNode, AXI4SlaveParameters, AXI4SlavePortParameters}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, LazyModule, LazyModuleImp, MemoryDevice, RegionType, TransferSizes}
import linknan.generator.RemoveCoreKey
import lntest.peripheral.AXI4MemorySlave
import org.chipsalliance.cde.config.Parameters
import xs.utils.perf.DebugOptionsKey
import zhujiang.ZJParametersKey
import zhujiang.axi.AxiParams

class DummyDramMoudle(memParams: AxiParams)(implicit p: Parameters) extends LazyModule{
  private val maw = p(ZJParametersKey).requestAddrBits - 1

  private val memDplmcMstParams = AXI4MasterPortParameters(
    masters = Seq(
      AXI4MasterParameters(
        name = "mem",
        id = IdRange(0, 1 << memParams.idBits)
      )
    )
  )

  private val memDplmcSlvParams = AXI4SlavePortParameters (
    slaves = Seq(
      AXI4SlaveParameters(
        address = AddressSet(0x0L, (1L << maw) - 1L).subtract(AddressSet(0x0L, 0x7FFFFFFFL)),
        regionType = RegionType.UNCACHED,
        executable = true,
        supportsRead = TransferSizes(1, 64),
        supportsWrite = TransferSizes(1, 64),
        interleavedId = Some(0),
        resources = (new MemoryDevice).reg("mem")
      )
    ),
    beatBytes = memParams.dataBits / 8
  )

  private val mstNode = AXI4MasterNode(Seq(memDplmcMstParams))
  private val slvNode = AXI4SlaveNode(Seq(memDplmcSlvParams))
  slvNode := mstNode
  lazy val module = new Impl

  class Impl extends LazyModuleImp(this) {

    val axi = mstNode.makeIOs()

    private val l_simAXIMem = AXI4MemorySlave(
      slvNode,
      16L * 1024 * 1024 * 1024,
      useBlackBox = true,
      dynamicLatency = p(DebugOptionsKey).UseDRAMSim,
      pureDram = p(RemoveCoreKey)
    )

    private val simAXIMem = Module(l_simAXIMem.module)
    l_simAXIMem.io_axi4.head <> slvNode.in.head._1
  }
}
