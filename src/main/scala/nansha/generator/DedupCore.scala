package nansha.generator

import firrtl.AnnotationSeq
import firrtl.ir._
import firrtl.options.Phase
import firrtl.stage.FirrtlCircuitAnnotation

object DedupHelper {
  val coreNamePat = "XSCore_?[0-9]+"
  def StatementsWalker(stmt:Statement):Statement = {
    stmt match {
      case s: DefInstance =>{
        if(s.module.matches(coreNamePat)){
          println(s"Rename ${s.module} calling to XSCore!")
          s.copy(module = "XSCore")
        } else {
          s
        }
      }
      case s: Conditionally => s.copy(conseq = StatementsWalker(s.conseq), alt = StatementsWalker(s.alt))
      case s: Block => {
        val stmts = s.stmts.map(StatementsWalker)
        s.copy(stmts = stmts)
      }
      case other => other
    }
  }
}

class DedupCore extends Phase {
  override def prerequisites: Seq[Nothing] = Seq.empty
  override def optionalPrerequisites: Seq[Nothing] = Seq.empty
  override def optionalPrerequisiteOf: Seq[Nothing] = Seq.empty
  override def invalidates(a: Phase) = false
  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val dedupAS = annotations.flatMap {
      case a: FirrtlCircuitAnnotation =>
        val mods = a.circuit.modules.map {
          case mm@Module(_, _, _, body) => {
            val nst = DedupHelper.StatementsWalker(body)
            mm.copy(body = nst)
          }
          case other => other
        }
        val nc = a.circuit.copy(modules = mods)
        Some(FirrtlCircuitAnnotation(nc))
      case a => Some(a)
    }
    AnnotationSeq(dedupAS)
  }
}
