package linknan.generator

import org.chipsalliance.cde.config.Parameters
import xs.utils.perf.DebugOptionsKey
import zhujiang.ZJParametersKey

import scala.annotation.tailrec

object ArgParser {
  def apply(args: Array[String]): (Parameters, Array[String]) = {
    val defaultConfig = new DefaultConfig
    var firrtlOpts = Array[String]()
    var hasHelp: Boolean = false

    @tailrec
    def parse(config: Parameters, args: List[String]): Parameters = {
      args match {
        case Nil => config

        case "--help" :: tail =>
          hasHelp = true
          parse(config, tail)

        case "--fpga-platform" :: tail =>
          parse(config.alter((site, here, up) => {
            case DebugOptionsKey => up(DebugOptionsKey).copy(FPGAPlatform = true)
          }), tail)

        case "--enable-difftest" :: tail =>
          parse(config.alter((site, here, up) => {
            case DebugOptionsKey => up(DebugOptionsKey).copy(EnableDifftest = true)
          }), tail)

        case "--basic-difftest" :: tail =>
          parse(config.alter((site, here, up) => {
            case DebugOptionsKey => up(DebugOptionsKey).copy(AlwaysBasicDiff = true)
          }), tail)

        case "--prefix" :: confString :: tail =>
          parse(config.alter((site, here, up) => {
            case ZJParametersKey => up(ZJParametersKey).copy(modulePrefix = confString + up(ZJParametersKey).modulePrefix)
            case ClusterPfxKey => confString + up(ClusterPfxKey)
          }), tail)

        case option :: tail =>
          firrtlOpts :+= option
          parse(config, tail)
      }
    }

    val cfg = parse(defaultConfig, args.toList)
    if(hasHelp) firrtlOpts :+= "--help"
    (cfg, firrtlOpts)
  }
}
