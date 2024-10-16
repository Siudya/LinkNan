package linknan.generator

import firrtl.AnnotationSeq
import firrtl.options.{Dependency, Phase, PhaseManager, Shell, Stage}
import circt.stage.CLI

trait XiangShanCli { this: Shell =>
  parser.note("XiangShan Options")
}

class LinkNanShell extends Shell("linknan") with CLI with XiangShanCli
class LinkNanStage extends Stage {
  override def prerequisites = Seq.empty
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq.empty
  override def invalidates(a: Phase) = false
  override val shell = new LinkNanShell
  override def run(annotations: AnnotationSeq): AnnotationSeq = {
    val pm = new PhaseManager(
      targets = Seq(
        Dependency[chisel3.stage.phases.Checks],
        Dependency[chisel3.stage.phases.AddImplicitOutputFile],
        Dependency[chisel3.stage.phases.AddImplicitOutputAnnotationFile],
        Dependency[chisel3.stage.phases.MaybeAspectPhase],
        Dependency[chisel3.stage.phases.AddSerializationAnnotations],
        Dependency[chisel3.stage.phases.Convert],
        Dependency[DedupCore],
        Dependency[chisel3.stage.phases.MaybeInjectingPhase],
        Dependency[circt.stage.phases.AddImplicitOutputFile],
        Dependency[circt.stage.phases.Checks],
        Dependency[circt.stage.phases.CIRCT]
      ),
      currentState = Seq(
        Dependency[firrtl.stage.phases.AddDefaults],
        Dependency[firrtl.stage.phases.Checks]
      )
    )
    pm.transform(annotations)
  }
}
