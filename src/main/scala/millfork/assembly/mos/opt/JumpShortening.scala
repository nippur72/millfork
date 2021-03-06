package millfork.assembly.mos.opt

import millfork.assembly.{Elidability, OptimizationContext}
import millfork.assembly.mos.{AddrMode, AssemblyLine, AssemblyLine0}
import millfork.{CompilationFlag, CompilationOptions}
import millfork.assembly.mos.Opcode._
import millfork.env.{Label, MemoryAddressConstant, NormalFunction}
import millfork.error.ConsoleLogger

/**
  * @author Karol Stasiak
  */
object JumpShortening {

  def validShortJump(thisOffset: Int, labelOffset: Int): Boolean = {
    val distance = labelOffset - (thisOffset + 2)
    distance.toByte == distance
  }

  def apply(f: NormalFunction, code: List[AssemblyLine], optimizationContext: OptimizationContext): List[AssemblyLine] = {
    val options = optimizationContext.options
    val offsets = new Array[Int](code.length)
    var o = 0
    code.zipWithIndex.foreach{
      case (line, ix) =>
        offsets(ix) = o
        o += line.sizeInBytes
    }
    val labelOffsets = code.zipWithIndex.flatMap {
      case (AssemblyLine0(LABEL, _, MemoryAddressConstant(Label(label))), ix) => Some(label -> offsets(ix))
      case _ => None
    }.toMap
    val cmos = options.flags(CompilationFlag.EmitCmosOpcodes)
    if (cmos) {
      code.zipWithIndex.map {
        case (line@AssemblyLine(JMP, AddrMode.Absolute, MemoryAddressConstant(Label(label)), Elidability.Elidable, _), ix) =>
          labelOffsets.get(label).fold(line) { labelOffset =>
            val thisOffset = offsets(ix)
            if (validShortJump(thisOffset, labelOffset)) {
              val result = line.copy(opcode = BRA, addrMode = AddrMode.Relative)
              options.log.debug("Changing branch from long to short")
              if (options.log.traceEnabled) {
                options.log.trace(line.toString)
                options.log.trace("     ↓")
                options.log.trace(result.toString)
              }
              result
            } else line
          }
          case (line, _) => line
      }
    } else {
      FlowAnalyzer.analyze(f, code, optimizationContext, FlowInfoRequirement.ForwardFlow).zipWithIndex.map {
        case ((info, line@AssemblyLine0(JMP, AddrMode.Absolute, MemoryAddressConstant(Label(label)))), ix) =>
          labelOffsets.get(label).fold(line) { labelOffset =>
            val thisOffset = offsets(ix)
            if (validShortJump(thisOffset, labelOffset)) {
              val bra =
                if (info.statusBefore.z.contains(true)) BEQ
                else if (info.statusBefore.z.contains(false)) BNE
                else if (info.statusBefore.n.contains(true)) BMI
                else if (info.statusBefore.n.contains(false)) BPL
                else if (info.statusBefore.v.contains(true)) BVS
                else if (info.statusBefore.v.contains(false)) BVC
                else if (info.statusBefore.c.contains(true)) BCS
                else if (info.statusBefore.c.contains(false)) BCC
                else JMP
              if (bra != JMP) {
                val result = line.copy(opcode = bra, addrMode = AddrMode.Relative)
                options.log.debug("Changing branch from long to short")
                if (options.log.traceEnabled) {
                  options.log.trace(info.statusBefore.toString)
                  options.log.trace(line.toString)
                  options.log.trace("     ↓")
                  options.log.trace(result.toString)
                }
                result
              } else line
            } else line
          }
        case ((_, line), _) => line
      }
    }
  }
}
