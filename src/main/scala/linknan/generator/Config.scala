package linknan.generator

import SimpleL2.Configs.{L2Param, L2ParamKey}
import org.chipsalliance.cde.config.{Config, _}
import xiangshan.cache.DCacheParameters
import xiangshan.{XSCoreParameters, XSCoreParamsKey}
import xijiang.tfb.TrafficBoardParams
import xijiang.{NodeParam, NodeType}
import xs.utils.perf.{DebugOptions, DebugOptionsKey}
import zhujiang.{ZJParameters, ZJParametersKey}

case object PrefixKey extends Field[String]
case object RemoveCoreKey extends Field[Boolean]

class FullNocConfig extends Config((site, here, up) => {
  case DebugOptionsKey => DebugOptions()
  case XSCoreParamsKey => XSCoreParameters()
  case L2ParamKey => L2Param(useDiplomacy = true)
  case PrefixKey => ""
  case RemoveCoreKey => false
  case ZJParametersKey => ZJParameters(
    localNodeParams = Seq(
      NodeParam(nodeType = NodeType.S, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.S, bankId = 1, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.HF, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.S, bankId = 2, splitFlit = true),
      NodeParam(nodeType = NodeType.S, bankId = 3, splitFlit = true),

      NodeParam(nodeType = NodeType.HI, defaultHni = true, splitFlit = true, attr = "cfg"),
      NodeParam(nodeType = NodeType.RI, attr = "dma", splitFlit = true),

      NodeParam(nodeType = NodeType.S, bankId = 3, splitFlit = true),
      NodeParam(nodeType = NodeType.S, bankId = 2, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.HF, bankId = 1, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.S, bankId = 1, splitFlit = true),
      NodeParam(nodeType = NodeType.S, bankId = 0, splitFlit = true),

      NodeParam(nodeType = NodeType.S, mainMemory = true, splitFlit = true, outstanding = 32, attr = "ddr_data"),
      NodeParam(nodeType = NodeType.HI, addressRange = (0x3803_0000, 0x3804_0000), splitFlit = true, attr = "ddr_cfg")
    )
  )
})

class ReducedNocConfig extends Config((site, here, up) => {
  case DebugOptionsKey => DebugOptions()
  case XSCoreParamsKey => XSCoreParameters()
  case L2ParamKey => L2Param(useDiplomacy = true)
  case PrefixKey => ""
  case RemoveCoreKey => false
  case ZJParametersKey => ZJParameters(
    localNodeParams = Seq(
      NodeParam(nodeType = NodeType.S, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.HF, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 2, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.S, bankId = 1, splitFlit = true),
      NodeParam(nodeType = NodeType.HI, defaultHni = true, splitFlit = true, attr = "cfg"),
      NodeParam(nodeType = NodeType.RI, attr = "dma", splitFlit = true),
      NodeParam(nodeType = NodeType.HI, addressRange = (0x3803_0000, 0x3804_0000), splitFlit = true, attr = "ddr_cfg"),
      NodeParam(nodeType = NodeType.S, mainMemory = true, splitFlit = true, outstanding = 32, attr = "ddr_data")
    )
  )
})

class MinimalNocConfig extends Config((site, here, up) => {
  case DebugOptionsKey => DebugOptions()
  case XSCoreParamsKey => XSCoreParameters()
  case L2ParamKey => L2Param(useDiplomacy = true)
  case PrefixKey => ""
  case RemoveCoreKey => false
  case ZJParametersKey => ZJParameters(
    localNodeParams = Seq(
      NodeParam(nodeType = NodeType.S, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.CC, cpuNum = 1, splitFlit = true, outstanding = 8, attr = "nanhu"),
      NodeParam(nodeType = NodeType.HF, bankId = 0, splitFlit = true),
      NodeParam(nodeType = NodeType.S, bankId = 1, splitFlit = true),
      NodeParam(nodeType = NodeType.HI, defaultHni = true, splitFlit = true, attr = "cfg"),
      NodeParam(nodeType = NodeType.RI, attr = "dma", splitFlit = true),
      NodeParam(nodeType = NodeType.HI, addressRange = (0x3803_0000, 0x3804_0000), splitFlit = true, attr = "ddr_cfg"),
      NodeParam(nodeType = NodeType.S, mainMemory = true, splitFlit = true, outstanding = 32, attr = "ddr_data")
    )
  )
})

class LLCConfig(sizeInMiB:Int = 16, ways:Int = 16, sfWays:Int = 16) extends Config((site, here, up) => {
  case ZJParametersKey => up(ZJParametersKey).copy(
      cacheSizeInMiB = sizeInMiB,
      cacheWays = ways,
      snoopFilterWays = sfWays
  )
})

class L2Config(sizeInKiB:Int = 1024, ways:Int = 8, slices:Int = 2) extends Config((site, here, up) => {
  case L2ParamKey => up(L2ParamKey).copy(
    ways = ways,
    nrSlice = slices,
    sets = sizeInKiB * 1024 / ways / slices / up(L2ParamKey).blockBytes
  )
  case ZJParametersKey => up(ZJParametersKey).copy(
      clusterCacheSizeInKiB = sizeInKiB
    )
  case XSCoreParamsKey => up(XSCoreParamsKey).copy(
    L2NBanks = slices
  )
})

class L1DConfig(sizeInKiB:Int = 64, ways:Int = 4) extends Config((site, here, up) => {
  case XSCoreParamsKey =>
    up(XSCoreParamsKey).copy(
    dcacheParametersOpt = Some(DCacheParameters(
      nSets = sizeInKiB * 1024 / ways / 64,
      nWays = ways,
      tagECC = Some("secded"),
      dataECC = Some("secded"),
      replacer = Some("setplru"),
      nMissEntries = 16,
      nProbeEntries = 8,
      nReleaseEntries = 18
    ))
  )
})

class FullConfig extends Config(
  new L1DConfig ++ new L2Config ++ new LLCConfig ++ new FullNocConfig
)

class ReducedConfig extends Config(
  new L1DConfig ++ new L2Config(512, 8) ++ new LLCConfig(4, 8) ++ new ReducedNocConfig
)

class MinimalConfig extends Config(
  new L1DConfig ++ new L2Config(256, 8) ++ new LLCConfig(2, 8) ++ new MinimalNocConfig
)