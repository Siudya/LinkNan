package linknan.generator

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}
import difftest.DifftestModule
import linknan.soc.LNTop
import xijiang.tfb.TrafficBoardFileManager
import xs.utils.FileRegisters
import zhujiang.ZJParametersKey

object Generator {
  val firtoolOps = Seq(
    FirtoolOption("-O=release"),
    FirtoolOption("--disable-all-randomization"),
    FirtoolOption("--disable-annotation-unknown"),
    FirtoolOption("--strip-debug-info"),
    FirtoolOption("--lower-memories"),
    FirtoolOption("--add-vivado-ram-address-conflict-synthesis-bug-workaround"),
    FirtoolOption("--lowering-options=noAlwaysComb," +
      " disallowPortDeclSharing, disallowLocalVariables," +
      " emittedLineLength=120, explicitBitcast," +
      " locationInfoStyle=plain, disallowMuxInlining")
  )
}

object SocGenerator extends App {
  val (config, firrtlOpts) = ArgParser(args)
  xs.utils.GlobalData.prefix = config(PrefixKey)
  difftest.GlobalData.prefix = config(PrefixKey)
  (new ChiselStage).execute(firrtlOpts, Generator.firtoolOps ++ Seq(
    ChiselGeneratorAnnotation(() => new LNTop()(config))
  ))

  if(config(ZJParametersKey).tfbParams.isDefined) TrafficBoardFileManager.release("cosim", "cosim", config)
  FileRegisters.write(filePrefix = config(PrefixKey) + "LNTop.")
}
