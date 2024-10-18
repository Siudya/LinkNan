/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package lntest.top

import chisel3._
import org.chipsalliance.cde.config
import lntest.peripheral._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{MemoryDevice, _}
import difftest._
import zhujiang.ZJParametersKey
import zhujiang.axi.AxiParams

class SimMMIO(cfgParams: AxiParams, dmaParams: AxiParams)(implicit p: config.Parameters) extends LazyModule {
  private val cfgDplmcParams = AXI4MasterPortParameters(
    masters = Seq(
      AXI4MasterParameters(
        name = "cfg",
        id = IdRange(0, 1 << cfgParams.idBits)
      )
    )
  )
  private val maw = p(ZJParametersKey).requestAddrBits - 1
  private val dmaDplmcSlvParams = AXI4SlavePortParameters (
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
    beatBytes = dmaParams.dataBits / 8
  )
  private val dmaDplmcMstParams = AXI4MasterPortParameters(
    masters = Seq(
      AXI4MasterParameters(
        name = "dma",
        id = IdRange(0, 1 << dmaParams.idBits)
      )
    )
  )

  private val node = AXI4MasterNode(Seq(cfgDplmcParams))
  val dma_node = AXI4SlaveNode(Seq(dmaDplmcSlvParams))

  private val flash = LazyModule(new AXI4Flash(Seq(AddressSet(0x10000000L, 0xfffffff))))
  private val uart = LazyModule(new AXI4UART(Seq(AddressSet(0x40600000L, 0xf))))
  private val intrGen = LazyModule(new AXI4IntrGenerator(Seq(AddressSet(0x40070000L, 0x0000ffffL))))
  private val dmaGen = LazyModule(new AXI4FakeDMA(Seq(AddressSet(0x37030000L, 0x0000ffffL)), dmaDplmcMstParams))

  private val axiBus = AXI4Xbar()

  uart.node := axiBus
  flash.node := axiBus
  intrGen.node := axiBus
  dmaGen.node := axiBus

  axiBus := node
  dma_node := dmaGen.dma_node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle() {
      val uart = new UARTIO
      val interrupt = new IntrGenIO
    })
    val cfg = node.makeIOs()
    val dma = dma_node.makeIOs()
    dontTouch(cfg)
    dontTouch(dma)
    io.uart <> uart.module.io.extra.get
    io.interrupt <> intrGen.module.io.extra.get
  }
}
