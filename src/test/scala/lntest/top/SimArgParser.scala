package lntest.top

import linknan.generator.{FullConfig, MinimalConfig, PrefixKey, ReducedConfig, RemoveCoreKey}
import org.chipsalliance.cde.config.Parameters
import xs.utils.perf.DebugOptionsKey

object SimArgParser {
  def apply(args: Array[String]): (Parameters, Array[String]) = {
    val configParam = args.filter(_ == "--config")
    val (configuration, stripCfgArgs) = if(configParam.isEmpty) {
      println("Config is not assigned, use Minimal Configuration!")
      (new MinimalConfig, args)
    } else {
      val pos = args.indexOf(configParam.head)
      val cfgStr = args(pos + 1)
      val res = cfgStr match {
        case "reduced" => new ReducedConfig
        case "full" => new FullConfig
        case _ => new MinimalConfig
      }
      val newArgs = args.zipWithIndex.filterNot(e => e._2 == pos || e._2 == (pos + 1)).map(_._1)
      (res, newArgs)
    }


    var firrtlOpts = Array[String]()

    def parse(config: Parameters, args: List[String]): Parameters = {
      args match {
        case Nil => config

        case "--dramsim3" :: tail =>
          parse(config.alter((site, here, up) => {
            case DebugOptionsKey => up(DebugOptionsKey).copy(UseDRAMSim = true)
          }), tail)

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

        case "--no-cores" :: tail =>
          parse(config.alter((site, here, up) => {
            case RemoveCoreKey => true
          }), tail)

        case "--prefix" :: confString :: tail =>
          parse(config.alter((site, here, up) => {
            case PrefixKey => confString
          }), tail)

        case option :: tail =>
          firrtlOpts :+= option
          parse(config, tail)
      }
    }

    val cfg = parse(configuration, stripCfgArgs.toList)
    (cfg, firrtlOpts)
  }
}
