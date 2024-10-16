package linknan.generator

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.{ChiselStage, FirtoolOption}
import linknan.cluster.CpuCluster
import linknan.soc.SoC
import xijiang.{Node, NodeType}

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
      " emittedLineLength=120, explicitBitcast, locationInfoStyle=plain," +
      " disallowExpressionInliningInPorts, disallowMuxInlining")
  )
}

object SocGenerator extends App {
  val (config, firrtlOpts) = ArgParser(args)
  xs.utils.GlobalData.prefix = config(PrefixKey)
  difftest.GlobalData.prefix = config(PrefixKey)
  (new LinkNanStage).execute(firrtlOpts, Generator.firtoolOps ++ Seq(
    ChiselGeneratorAnnotation(() => new SoC()(config))
  ))
}
