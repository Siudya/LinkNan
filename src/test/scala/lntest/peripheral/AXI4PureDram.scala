package lntest.peripheral

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.experimental.ExtModule
import chisel3.util._
import freechips.rocketchip.amba.axi4.{AXI4Parameters, AXI4SlaveNode}
import freechips.rocketchip.diplomacy.{AddressSet, LazyModule}
import xs.utils._

class DramRWHelper extends ExtModule with HasExtModuleInline {
  val DataBits = 64

  val clock = IO(Input(Clock()))
  val reset = IO(Input(Reset()))
  val ren   = IO(Input(Bool()))
  val rIdx  = IO(Input(UInt(DataBits.W)))
  val rdata = IO(Output(UInt(DataBits.W)))
  val wen   = IO(Input(Bool()))
  val wIdx  = IO(Input(UInt(DataBits.W)))
  val wdata = IO(Input(UInt(DataBits.W)))
  val wmask = IO(Input(UInt(DataBits.W)))

  def read(enable: Bool, address: UInt): UInt = {
    ren := enable
    rIdx := address
    rdata
  }
  def write(enable: Bool, address: UInt, data: UInt, mask: UInt): Unit = {
    wen := enable
    wIdx := address
    wdata := data
    wmask := mask
  }

  val verilogLines = Seq(
    "module DramRWHelper(",
    "  input         clock,",
    "  input         reset,",
    "  input         ren,",
    "  input  [63:0] rIdx,",
    "  output [63:0] rdata,",
    "  input  [63:0] wIdx,",
    "  input  [63:0] wdata,",
    "  input  [63:0] wmask,",
    "  input         wen",
    ");",
    "",
    """  import "DPI-C" function void ram_write_helper (""",
    """    input  longint    wIdx,""",
    """    input  longint    wdata,""",
    """    input  longint    wmask,""",
    """    input  bit        wen""",
    """  );""",
    "",
    """  import "DPI-C" function longint ram_read_helper (""",
    """    input  bit        en,""",
    """    input  longint    rIdx""",
    """  );""",
    "  assign rdata = (!reset && ren) ? ram_read_helper(1, rIdx) : 64'b0;",
    "",
    "  always @(posedge clock) begin",
    "    if (!reset && wen) begin",
    "      ram_write_helper(wIdx, wdata, wmask, 1);",
    "    end",
    "  end",
    "",
    "endmodule"
  )
  setInline(s"$desiredName.sv", verilogLines.mkString("\n"))
}

object DramRWHelper {
  def apply(clock: Clock, reset: Reset): DramRWHelper = {
    val helper = Module(new DramRWHelper)
    helper.clock := clock
    helper.reset := reset
    helper
  }
}

class DramRequestHelper(requestType: Int)
  extends ExtModule(Map("REQUEST_TYPE" -> requestType))
    with HasExtModuleInline
{
  val clock     = IO(Input(Clock()))
  val reset     = IO(Input(Reset()))
  val io = IO(new Bundle {
    val req = Flipped(ValidIO(new Bundle {
      val addr = UInt(64.W)
      val id   = UInt(32.W)
    }))
    val response = Output(Bool())
  })

  val verilogLines = Seq(
    "import \"DPI-C\" function bit memory_request (",
    "  input longint address,",
    "  input int id,",
    "  input bit isWrite",
    ");",
    "",
    "module DramRequestHelper #(",
    "  parameter REQUEST_TYPE",
    ")(",
    "  input             clock,",
    "  input             reset,",
    "  input             io_req_valid,",
    "  input      [63:0] io_req_bits_addr,",
    "  input      [31:0] io_req_bits_id,",
    "  output reg        io_response",
    ");",
    "",
    "always @(posedge clock or posedge reset) begin",
    "  if (reset) begin",
    "    io_response <= 1'b0;",
    "  end",
    "  else if (io_req_valid) begin",
    "    io_response <= memory_request(io_req_bits_addr, io_req_bits_id, REQUEST_TYPE);",
    "  end" +
      "  else begin",
    "    io_response <= 1'b0;",
    "  end",
    "end",
    "",
    "endmodule"
  )
  setInline(s"$desiredName.sv", verilogLines.mkString("\n"))
}

class DramResponseHelper(requestType: Int)
  extends ExtModule(Map("REQUEST_TYPE" -> requestType))
    with HasExtModuleInline
{
  val clock    = IO(Input(Clock()))
  val reset    = IO(Input(Reset()))
  val enable   = IO(Input(Bool()))
  val response = IO(Output(UInt(64.W)))

  val verilogLines = Seq(
    "import \"DPI-C\" function longint memory_response (",
    "  input bit isWrite",
    ");",
    "",
    "module DramResponseHelper #(",
    "  parameter REQUEST_TYPE",
    ")(",
    "  input             clock,",
    "  input             reset,",
    "  input             enable,",
    "  output reg [63:0] response",
    ");",
    "",
    "always @(posedge clock or posedge reset) begin",
    "  if (reset) begin",
    "    response <= 64'b0;",
    "  end",
    "  else if (!reset && enable) begin",
    "    response <= memory_response(REQUEST_TYPE);",
    "  end",
    " else begin",
    "    response <= 64'b0;",
    "  end",
    "end",
    "",
    "endmodule"
  )
  setInline(s"$desiredName.sv", verilogLines.mkString("\n"))
}

trait DramHelper { this: Module =>
  private def requestType(isWrite: Boolean): Int = if (isWrite) 1 else 0
  private def request(valid: Bool, addr: UInt, id: UInt, isWrite: Boolean): Bool = {
    val helper = Module(new DramRequestHelper(requestType(isWrite)))
    helper.clock := clock
    helper.reset := reset
    helper.io.req.valid := valid
    helper.io.req.bits.addr := addr
    helper.io.req.bits.id := id
    helper.io.response
  }
  protected def readRequest(valid: Bool, addr: UInt, id: UInt): Bool =
    request(valid, addr, id, false)
  protected def writeRequest(valid: Bool, addr: UInt, id: UInt): Bool =
    request(valid, addr, id, true)
  private def response(enable: Bool, isWrite: Boolean): (Bool, UInt) = {
    val helper = Module(new DramResponseHelper(requestType(isWrite)))
    helper.clock := clock
    helper.reset := reset
    helper.enable := enable
    (helper.response(32), helper.response(31, 0))
  }
  protected def readResponse(enable: Bool): (Bool, UInt) =
    response(enable, false)
  protected def writeResponse(enable: Bool): (Bool, UInt) =
    response(enable, true)
}

class AXI4DramMemoryImp[T <: Data](outer: AXI4DramMemory) extends AXI4SlaveModuleImp(outer) with DramHelper {
  val ramWidth = 8
  val ramSplit = outer.beatBytes / ramWidth
  val ramBaseAddr = outer.address.head.base
  val ramOffsetBits = log2Ceil(outer.memByte)
  def ramIndex(addr: UInt) = ((addr - ramBaseAddr.U)(ramOffsetBits - 1, 0) >> log2Ceil(in.w.bits.data.getWidth/8)).asUInt // 3 5
  val ramHelper = Seq.fill(ramSplit)(DramRWHelper(clock, reset))

  val numOutstanding = 1 << in.ar.bits.id.getWidth
  val addressMem = Mem(numOutstanding, UInt(in.ar.bits.addr.getWidth.W))
  val arlenMem = Mem(numOutstanding, UInt(in.ar.bits.len.getWidth.W))

  println(s"[AXI4Memory] ramWidth: ${ramWidth}")
  println(s"[AXI4Memory] ramSplit: ${ramSplit}")
  println(s"[AXI4Memory] ramBaseAddr: ${ramBaseAddr}")
  println(s"[AXI4Memory] ramOffsetBits: ${ramOffsetBits}")

  // accept a read request and send it to the external model
  val pending_read_req_valid = RegInit(false.B)
  val pending_read_req_bits  = RegEnable(in.ar.bits, in.ar.fire)
  val pending_read_req_ready = Wire(Bool())
  val pending_read_need_req = pending_read_req_valid && !pending_read_req_ready
  val read_req_valid = pending_read_need_req || in.ar.valid
  val read_req_bits  = Mux(pending_read_need_req, pending_read_req_bits, in.ar.bits)
  pending_read_req_ready := readRequest(read_req_valid, read_req_bits.addr, read_req_bits.id)

  when (in.ar.fire) {
    pending_read_req_valid := true.B
    addressMem.write(read_req_bits.id, read_req_bits.addr)
    arlenMem.write(read_req_bits.id, read_req_bits.len)
  }.elsewhen (pending_read_req_ready) {
    pending_read_req_valid := false.B
  }
  in.ar.ready := !pending_read_req_valid || pending_read_req_ready

  // accept a write request (including address and data) and send it to the external model
  val pending_write_req_valid = RegInit(VecInit.fill(2)(false.B))
  val pending_write_req_bits  = RegEnable(in.aw.bits, in.aw.fire)
  val pending_write_req_data  = RegEnable(in.w.bits, in.w.fire)
  val pending_write_req_ready = Wire(Bool())
  val pending_write_need_req = pending_write_req_valid.last && !pending_write_req_ready
  val write_req_valid = pending_write_req_valid.head && (pending_write_need_req || in.w.fire && in.w.bits.last); dontTouch(write_req_valid)
  pending_write_req_ready := writeRequest(write_req_valid, pending_write_req_bits.addr, pending_write_req_bits.id)

  when (in.aw.fire) {
    pending_write_req_valid.head := true.B
  }.elsewhen (pending_write_req_ready) {
    pending_write_req_valid.head := false.B
  }
  val write_req_last = in.w.fire && in.w.bits.last
  when (write_req_last) {
    pending_write_req_valid.last := true.B
  }.elsewhen (pending_write_req_ready) {
    pending_write_req_valid.last := false.B
  }
  in.aw.ready := !pending_write_req_valid.head
  in.w.ready := in.aw.ready || !pending_write_req_valid.last

  // ram is written when write data fire
  val wdata_cnt = Counter(outer.burstLen)
  val write_req_addr = Mux(in.aw.fire, in.aw.bits.addr, pending_write_req_bits.addr)
  val write_req_index = Cat(ramIndex(write_req_addr)+wdata_cnt.value, 0.U(log2Ceil(ramSplit).W)); dontTouch(write_req_index)
  for ((ram, i) <- ramHelper.zipWithIndex) {
    val enable = in.w.fire
    val address = write_req_index + i.U
    val data = in.w.bits.data(ramWidth * 8 * i + 63, ramWidth * 8 * i)
    val mask = MaskExpand(in.w.bits.strb(i * 8 + 7, i * 8))
    ram.write(enable, address, data, mask)
  }
  when (write_req_last) {
    wdata_cnt.reset()
  }.elsewhen (in.w.fire) {
    wdata_cnt.inc()
  }

  // read data response
  val pending_read_resp_valid = RegInit(false.B)
  val pending_read_resp_id = Reg(UInt(in.r.bits.id.getWidth.W))
  val has_read_resp = Wire(Bool())
  val read_resp_last = in.r.fire && in.r.bits.last
  val (read_resp_valid, read_resp_id) = readResponse(!has_read_resp || read_resp_last)
  has_read_resp := (read_resp_valid && !read_resp_last) || pending_read_resp_valid
  val rdata_cnt = Counter(outer.burstLen)
  val read_resp_index = Cat(ramIndex(addressMem(in.r.bits.id))+rdata_cnt.value, 0.U(log2Ceil(ramSplit).W)); dontTouch(read_resp_index)
  val read_resp_len = arlenMem(in.r.bits.id)
  in.r.valid := read_resp_valid || pending_read_resp_valid
  in.r.bits.id := Mux(pending_read_resp_valid, pending_read_resp_id, read_resp_id)
  val rdata = ramHelper.zipWithIndex.map{ case (ram, i) => ram.read(in.r.valid, read_resp_index + i.U) }
  in.r.bits.data := VecInit(rdata).asUInt
  in.r.bits.resp := AXI4Parameters.RESP_OKAY
  in.r.bits.last := (rdata_cnt.value === read_resp_len)

  when (!pending_read_resp_valid && read_resp_valid && !read_resp_last) {
    pending_read_resp_valid := true.B
    pending_read_resp_id := read_resp_id
  }.elsewhen (pending_read_resp_valid && !read_resp_valid && read_resp_last) {
    pending_read_resp_valid := false.B
  }
  when (read_resp_last) {
    rdata_cnt.reset()
  }.elsewhen (in.r.fire) {
    rdata_cnt.inc()
  }

  // write response
  val pending_write_resp_valid = RegInit(false.B)
  val pending_write_resp_id = Reg(UInt(in.b.bits.id.getWidth.W))
  val has_write_resp = Wire(Bool())
  val (write_resp_valid, write_resp_id) = writeResponse(!has_write_resp || in.b.fire)
  has_write_resp := write_resp_valid || pending_write_resp_valid
  in.b.valid := write_resp_valid || pending_write_resp_valid
  in.b.bits.id := Mux(pending_write_resp_valid, pending_write_resp_id, write_resp_id)
  in.b.bits.resp := AXI4Parameters.RESP_OKAY

  when (!pending_write_resp_valid && write_resp_valid && !in.b.ready) {
    pending_write_resp_valid := true.B
    pending_write_resp_id := write_resp_id
  }.elsewhen (pending_write_resp_valid && !write_resp_valid && in.b.ready) {
    pending_write_resp_valid := false.B
  }
}

class AXI4DramMemory
(
  val address: Seq[AddressSet],
  val memByte: Long,
  val useBlackBox: Boolean = false,
  val executable: Boolean = true,
  val beatBytes: Int,
  val burstLen: Int,
)(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable, beatBytes, burstLen)
{
  override lazy val module = new AXI4DramMemoryImp(this)
}

class AXI4PureDram (
  slave: AXI4SlaveNode,
  memByte: Long,
  useBlackBox: Boolean = false
)(implicit p: Parameters) extends AXI4MemorySlave(slave, memByte, useBlackBox) {
  val ram = LazyModule(new AXI4DramMemory(
    slaveParam.address,
    memByte,
    useBlackBox,
    slaveParam.executable,
    portParam.beatBytes,
    burstLen
  ))
  ram.node := master
}
