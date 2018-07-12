package millfork.parser

import java.util.Locale

import fastparse.all.{parserApi, _}
import fastparse.core
import millfork.CompilationOptions
import millfork.assembly.z80.{ZOpcode, _}
import millfork.env.{ByZRegister, Constant, ParamPassingConvention}
import millfork.error.ErrorReporting
import millfork.node._

/**
  * @author Karol Stasiak
  */
case class Z80Parser(filename: String, input: String, currentDirectory: String, options: CompilationOptions, featureConstants: Map[String, Long]) extends MfParser[ZLine](filename, input, currentDirectory, options, featureConstants) {

  import MfParser._

  private val zero = LiteralExpression(0, 1)

  val appcSimple: P[ParamPassingConvention] = (P("hl" | "bc" | "de" | "a" | "b" | "c" | "d" | "e" | "h" | "l").! ~ !letterOrDigit).map {
    case "a" => ByZRegister(ZRegister.A)
    case "b" => ByZRegister(ZRegister.B)
    case "c" => ByZRegister(ZRegister.C)
    case "d" => ByZRegister(ZRegister.D)
    case "e" => ByZRegister(ZRegister.E)
    case "h" => ByZRegister(ZRegister.H)
    case "l" => ByZRegister(ZRegister.L)
    case "hl" => ByZRegister(ZRegister.HL)
    case "bc" => ByZRegister(ZRegister.BC)
    case "de" => ByZRegister(ZRegister.DE)
    case x => ErrorReporting.fatal(s"Unknown assembly parameter passing convention: `$x`")
  }

  override val asmParamDefinition: P[ParameterDeclaration] = for {
    p <- position()
    typ <- identifier ~ SWS
    appc <- appcSimple | appcComplex
  } yield ParameterDeclaration(typ, appc).pos(p)

  // TODO: label and instruction in one line
  val asmLabel: P[ExecutableStatement] = (identifier ~ HWS ~ ":" ~/ HWS).map(l => Z80AssemblyStatement(ZOpcode.LABEL, NoRegisters, None, VariableExpression(l), elidable = true))

  val asmMacro: P[ExecutableStatement] = ("+" ~/ HWS ~/ functionCall).map(ExpressionStatement)

  private val toRegister: Map[String, ZRegister.Value] = Map(
    "A" -> ZRegister.A, "a" -> ZRegister.A,
    "B" -> ZRegister.B, "b" -> ZRegister.B,
    "C" -> ZRegister.C, "c" -> ZRegister.C,
    "D" -> ZRegister.D, "d" -> ZRegister.D,
    "E" -> ZRegister.E, "e" -> ZRegister.E,
    "H" -> ZRegister.H, "h" -> ZRegister.H,
    "L" -> ZRegister.L, "l" -> ZRegister.L,
    "HL" -> ZRegister.HL, "hl" -> ZRegister.HL,
    "AF" -> ZRegister.AF, "af" -> ZRegister.AF,
    "BC" -> ZRegister.BC, "bc" -> ZRegister.BC,
    "DE" -> ZRegister.DE, "de" -> ZRegister.DE,
    "IX" -> ZRegister.IX, "ix" -> ZRegister.IX,
    "IY" -> ZRegister.IY, "iy" -> ZRegister.IY,
    "SP" -> ZRegister.SP, "sp" -> ZRegister.SP,
  )
  
  private def param(allowAbsolute: Boolean): P[(ZRegister.Value, Option[Expression])] = asmExpressionWithParens.map {
    case (VariableExpression(r), false) if toRegister.contains(r)=> (toRegister(r), None)
    case (VariableExpression("HL" | "hl"), true) => (ZRegister.MEM_HL, None)
    case (VariableExpression("BC" | "bc"), true) => (ZRegister.MEM_BC, None)
    case (VariableExpression("DE" | "de"), true) => (ZRegister.MEM_DE, None)
    case (FunctionCallExpression("IX" | "ix", List(o)), true) => (ZRegister.MEM_IX_D, Some(o))
    case (FunctionCallExpression("IY" | "iy", List(o)), true) => (ZRegister.MEM_IY_D, Some(o))
    case (e, true) if allowAbsolute => (ZRegister.MEM_ABS_8, Some(e))
    case (e, _) => (ZRegister.IMM_8, Some(e))
  }

  def mapOne8Register(op: ZOpcode.Value)(param: (ZRegister.Value, Option[Expression])): (ZOpcode.Value, OneRegister, Option[Expression], Expression) = param match {
    case (reg@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(e)) => (op, OneRegister(reg), Some(e), zero)
    case (reg, addr) => (op, OneRegister(reg), None, addr.getOrElse(zero))
  }

  def one8Register(op: ZOpcode.Value): P[(ZOpcode.Value, OneRegister, Option[Expression], Expression)] = param(allowAbsolute = false).map{
    case (reg@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(e)) => (op, OneRegister(reg), Some(e), zero)
    case (reg, addr) => (op, OneRegister(reg), None, addr.getOrElse(zero))
  }

  def one16Register(op: ZOpcode.Value): P[(ZOpcode.Value, OneRegister, Option[Expression], Expression)] = param(allowAbsolute = false).map{
    case (reg@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(e)) => (op, OneRegister(reg), Some(e), zero)
    case (ZRegister.MEM_ABS_8, addr) => (op, OneRegister(ZRegister.MEM_ABS_16), None, addr.getOrElse(zero))
    case (ZRegister.IMM_8, addr) => (op, OneRegister(ZRegister.IMM_16), None, addr.getOrElse(zero))
    case (reg, addr) => (op, OneRegister(reg), None, addr.getOrElse(zero))
  }

  private val jumpCondition: P[ZRegisters] = (HWS ~ (
    "NZ" | "nz" | "nc" | "NC" |
      "PO" | "po" | "PE" | "pe" |
      "m" | "M" | "p" | "P" |
      "c" | "C" | "Z" | "z").! ~ HWS).map {
    case "Z" | "z" => IfFlagSet(ZFlag.Z)
    case "PE" | "pe" => IfFlagSet(ZFlag.P)
    case "C" | "c" => IfFlagSet(ZFlag.C)
    case "M" | "m" => IfFlagSet(ZFlag.S)
    case "NZ" | "nz" => IfFlagClear(ZFlag.Z)
    case "PO" | "po" => IfFlagClear(ZFlag.P)
    case "NC" | "nc" => IfFlagClear(ZFlag.C)
    case "P" | "p" => IfFlagClear(ZFlag.S)
    case _ => NoRegisters // shouldn't happen
  }

  private def is16Bit(r: ZRegister.Value): Boolean = r match {
    case ZRegister.HL => true
    case ZRegister.BC => true
    case ZRegister.DE => true
    case ZRegister.IX => true
    case ZRegister.IY => true
    case ZRegister.SP => true
    case ZRegister.AF => true
    case ZRegister.MEM_ABS_16 => true
    case ZRegister.IMM_16 => true
    case _ => false
  }

  private def merge(op8: ZOpcode.Value, op16: ZOpcode.Value, skipTargetA: Boolean)
                   (params: (ZRegister.Value, Option[Expression], ZRegister.Value, Option[Expression]))
  : (ZOpcode.Value, ZRegisters, Option[Expression], Expression) = params match {
    case (ZRegister.A, _, s, e) if skipTargetA => mapOne8Register(op8)(s -> e)

    case (tr@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(o), sr@(ZRegister.MEM_ABS_8 | ZRegister.IMM_8), Some(v)) =>
      (op8, TwoRegisters(tr, sr), Some(o), v)
    case (tr@(ZRegister.MEM_ABS_8 | ZRegister.IMM_8), Some(v), sr@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(o)) =>
      (op8, TwoRegisters(tr, sr), Some(o), v)

    case (tr@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(o), sr, _) =>
      (op8, TwoRegisters(tr, sr), Some(o), zero)
    case (tr, _, sr@(ZRegister.MEM_IX_D | ZRegister.MEM_IY_D), Some(o)) =>
      (op8, TwoRegisters(tr, sr), Some(o), zero)

    case (tr, _, ZRegister.MEM_ABS_8, Some(v)) if is16Bit(tr) =>
      (op16, TwoRegisters(tr, ZRegister.MEM_ABS_16), None, v)
    case (tr, _, ZRegister.IMM_8, Some(v)) if is16Bit(tr) =>
      (op16, TwoRegisters(tr, ZRegister.IMM_16), None, v)
    case (ZRegister.MEM_ABS_8, Some(v), sr, _) if is16Bit(sr) =>
      (op16, TwoRegisters(ZRegister.MEM_ABS_16, sr), None, v)
    case (ZRegister.IMM_8, Some(v), sr, _) if is16Bit(sr) =>
      (op16, TwoRegisters(ZRegister.IMM_16, sr), None, v)

    case (t, Some(v), s, None) =>
      (op8, TwoRegisters(t, s), None, v)
    case (t, None, s, Some(v)) =>
      (op8, TwoRegisters(t, s), None, v)
    case (t, None, s, None) =>
      (op8, TwoRegisters(t, s), None, zero)
    case _ => ???
  }

  private val jumpConditionWithComma: P[ZRegisters] = (jumpCondition ~ "," ~/ HWS).?.map (_.getOrElse(NoRegisters))
  private val jumpConditionWithoutComma: P[ZRegisters] = (jumpCondition ~/ HWS).?.map (_.getOrElse(NoRegisters))

  val asmInstruction: P[ExecutableStatement] = {
    import ZOpcode._
    for {
      el <- elidable
      pos <- position()
      opcode: String <- identifier ~/ HWS
      tuple4/*: (ZOpcode.Value, ZRegisters, Option[Expression], Expression)*/ <- opcode.toUpperCase(Locale.ROOT) match {
        case "RST" => asmExpression.map((RST, NoRegisters, None, _))
        case "EI" => imm(EI)
        case "DI" => imm(DI)
        case "HALT" => imm(HALT)

        case "RETN" => imm(RETN)
        case "RETI" => imm(RETI)
        case "RET" => P(jumpConditionWithoutComma).map((RET, _, None, zero))
        case "CALL" => (jumpConditionWithComma~asmExpression).map{case (reg, param) => (CALL, reg, None, param)}
        case "JP" => (jumpConditionWithComma~asmExpression).map{case (reg, param) => (JP, reg, None, param)}
        case "JR" => (jumpConditionWithComma~asmExpression).map{case (reg, param) => (JR, reg, None, param)}
        case "DJNZ" => asmExpression.map((DJNZ, NoRegisters, None, _))

        case "CP" => one8Register(CP)
        case "AND" => one8Register(AND)
        case "OR" => one8Register(OR)
        case "XOR" => one8Register(XOR)
        case "SUB" => one8Register(SUB)
        case "DEC" => one8Register(DEC)
        case "INC" => one8Register(INC)

        case "RLA" => regA(RL)
        case "RRA" => regA(RR)
        case "RLCA" => regA(RLC)
        case "RRCA" => regA(RRC)
        case "RL" => one8Register(RL)
        case "RR" => one8Register(RR)
        case "RLC" => one8Register(RLC)
        case "RRC" => one8Register(RRC)
        case "SLA" => one8Register(SLA)
        case "SLL" => one8Register(SLL)
        case "SRA" => one8Register(SRA)
        case "SRL" => one8Register(SRL)

        case "SCF" => imm(SCF)
        case "CCF" => imm(CCF)
        case "CPL" => imm(CPL)
        case "DAA" => imm(DAA)
        case "EXX" => imm(EXX)
        case "NOP" => imm(NOP)

        case "LDI" => imm(LDI)
        case "LDD" => imm(LDD)
        case "LDIR" => imm(LDIR)
        case "LDDR" => imm(LDDR)
        case "CPI" => imm(CPI)
        case "CPD" => imm(CPD)
        case "CPIR" => imm(CPIR)
        case "CPDR" => imm(CPDR)
        case "INI" => imm(INI)
        case "IND" => imm(IND)
        case "INIR" => imm(INIR)
        case "INDR" => imm(INDR)
        case "OUTI" => imm(OUTI)
        case "OUTD" => imm(OUTD)
        case "OUTIR" => imm(OUTIR)
        case "OUTDR" => imm(OUTDR)
        case "OTIR" => imm(OUTIR)
        case "OTDR" => imm(OUTDR)

        case "PUSH" => one16Register(PUSH)
        case "POP" => one16Register(POP)

        case "IN" => (asmExpressionWithParens ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ asmExpressionWithParens).map { p: (Expression, Boolean, (Expression, Boolean)) =>
          p match {
            case (VariableExpression(r), false, (VariableExpression("C" | "c"), true))
              if toRegister.contains(r) =>
              (IN_C, OneRegister(toRegister(r)), None, zero)
            case (VariableExpression(r), false, (port, true))
              if toRegister.contains(r) =>
              (IN_IMM, OneRegister(toRegister(r)), None, port)
            case _ =>
              ErrorReporting.error("Invalid parameters for IN", Some(pos))
              (NOP, NoRegisters, None, zero)
          }
        }
        case "OUT" => (asmExpressionWithParens ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ asmExpressionWithParens).map { p: (Expression, Boolean, (Expression, Boolean)) =>
          p match {
            case (VariableExpression("C" | "c"), true, (VariableExpression(r), false))
              if toRegister.contains(r) =>
              (OUT_C, OneRegister(toRegister(r)), None, zero)
            case (port, true, (VariableExpression(r), false))
              if toRegister.contains(r) =>
              (OUT_IMM, OneRegister(toRegister(r)), None, port)
            case _ =>
              ErrorReporting.error("Invalid parameters for OUT", Some(pos))
              (NOP, NoRegisters, None, zero)
          }
        }
        case "EX" => (asmExpressionWithParens ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ asmExpressionWithParens).map { p: (Expression, Boolean, (Expression, Boolean)) =>
          p match {
            case (VariableExpression("AF" | "af"), false, (VariableExpression("AF" | "af"), true)) =>
              (EX_AF_AF, NoRegisters, None, zero)
            case (VariableExpression("DE" | "de"), false, (VariableExpression("HL" | "hl"), false)) =>
              (EX_DE_HL, NoRegisters, None, zero)
            case (VariableExpression("HL" | "hl"), false, (VariableExpression("DE" | "de"), false)) =>
              (EX_DE_HL, NoRegisters, None, zero)
            case (VariableExpression("SP" | "sp"), true, (VariableExpression(r), false))
              if toRegister.contains(r) =>
              (EX_SP, OneRegister(toRegister(r)), None, zero)
            case (VariableExpression(r), false, (VariableExpression("SP" | "sp"), true))
              if toRegister.contains(r) =>
              (EX_SP, OneRegister(toRegister(r)), None, zero)
            case _ =>
              ErrorReporting.error("Invalid parameters for EX", Some(pos))
              (NOP, NoRegisters, None, zero)
          }
        }

        case "LD" => (param(true) ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ param(true)).map {
          case (r1, e1, (r2, e2)) => merge(LD, LD_16, skipTargetA = false)((r1, e1, r2, e2))
        }
        case "ADD" => (param(false) ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ param(false)).map {
          case (r1, e1, (r2, e2)) => merge(ADD, ADD_16, skipTargetA = true)((r1, e1, r2, e2))
        }
        case "ADC" => (param(false) ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ param(false)).map {
          case (r1, e1, (r2, e2)) => merge(ADC, ADC_16, skipTargetA = true)((r1, e1, r2, e2))
        }
        case "SBC" => (param(false) ~ HWS ~ position("comma").map(_ => ()) ~ "," ~/ HWS ~ param(false)).map {
          case (r1, e1, (r2, e2)) => merge(SBC, SBC_16, skipTargetA = true)((r1, e1, r2, e2))
        }

        case _ =>
          ErrorReporting.error("Unsupported opcode " + opcode, Some(pos))
          imm(NOP)
      }
    } yield {
      val (actualOpcode, registers, offset, param) = tuple4
      Z80AssemblyStatement(actualOpcode, registers, offset, param, el)
    }
  }

  private def imm(opcode: ZOpcode.Value): P[(ZOpcode.Value, ZRegisters, Option[Expression], Expression)] =
    P("").map(_=>(opcode, NoRegisters, None, zero))

  private def regA(opcode: ZOpcode.Value): P[(ZOpcode.Value, ZRegisters, Option[Expression], Expression)] =
    P("").map(_=>(opcode, OneRegister(ZRegister.A), None, zero))

  override val asmStatement: P[ExecutableStatement] = (position("assembly statement") ~ P(asmLabel | asmMacro | arrayContentsForAsm | asmInstruction)).map { case (p, s) => s.pos(p) } // TODO: macros

  override def validateAsmFunctionBody(p: Position, flags: Set[String], name: String, statements: Option[List[Statement]]): Unit = {
    statements match {
      case Some(Nil) => ErrorReporting.warn("Assembly function `$name` is empty, did you mean RET, RETI, RETN or JP", options, Some(p))
      case Some(xs) =>
        if (flags("interrupt")) {
          if (xs.exists {
            case Z80AssemblyStatement(ZOpcode.RET, _, _, _, _) => true
            case _ => false
          }) ErrorReporting.warn("Assembly interrupt function `$name` contains RET, did you mean RETI/RETN?", options, Some(p))
        } else {
          if (xs.exists {
            case Z80AssemblyStatement(ZOpcode.RETI, _, _, _, _) => true
            case Z80AssemblyStatement(ZOpcode.RETN, _, _, _, _) => true
            case _ => false
          }) ErrorReporting.warn("Assembly non-interrupt function `$name` contains RETI or RETN, did you mean RET?", options, Some(p))
        }
        if (!name.startsWith("__") && !flags("macro")) {
          xs.last match {
            case Z80AssemblyStatement(ZOpcode.RET, NoRegisters, _, _, _) => () // OK
            case Z80AssemblyStatement(ZOpcode.RETN, NoRegisters, _, _, _) => () // OK
            case Z80AssemblyStatement(ZOpcode.RETI, NoRegisters, _, _, _) => () // OK
            case Z80AssemblyStatement(ZOpcode.JP, NoRegisters, _, _, _) => () // OK
            case Z80AssemblyStatement(ZOpcode.JR, NoRegisters, _, _, _) => () // OK
            case _ =>
              val validReturn = if (flags("interrupt")) "RETI/RETN" else "RET"
              ErrorReporting.warn(s"Non-macro assembly function `$name` should end in " + validReturn, options, Some(p))
          }
        }
      case None => ()
    }
  }
}
