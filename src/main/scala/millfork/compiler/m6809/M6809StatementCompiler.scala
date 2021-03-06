package millfork.compiler.m6809

import millfork.{CompilationFlag, CompilationOptions}
import millfork.assembly.{BranchingOpcodeMapping, Elidability}
import millfork.assembly.m6809.{Inherent, MLine, MOpcode, NonExistent}
import millfork.compiler.{AbstractCompiler, AbstractExpressionCompiler, AbstractStatementCompiler, BranchSpec, CompilationContext}
import millfork.node.{Assignment, BlackHoleExpression, BreakStatement, ContinueStatement, DoWhileStatement, EmptyStatement, ExecutableStatement, Expression, ExpressionStatement, ForEachStatement, ForStatement, FunctionCallExpression, GotoStatement, IfStatement, LabelStatement, LiteralExpression, M6809AssemblyStatement, MemsetStatement, ReturnDispatchStatement, ReturnStatement, VariableExpression, WhileStatement}
import millfork.assembly.m6809.MOpcode._
import millfork.env.{BooleanType, ConstantBooleanType, FatBooleanType, Label, MemoryAddressConstant, StructureConstant, ThingInMemory}

/**
  * @author Karol Stasiak
  */
object M6809StatementCompiler extends AbstractStatementCompiler[MLine] {
  def compile(ctx: CompilationContext, statement: ExecutableStatement): (List[MLine], List[MLine]) = {
    val env = ctx.env
    val epilogue = if (ctx.function.stackVariablesSize == 0) Nil else {
      import millfork.node.M6809Register.{U, Y}
      if (ctx.options.flag(CompilationFlag.UseUForStack)) {
        List(
          MLine.indexedS(LEAS, ctx.function.stackVariablesSize),
          MLine.pp(PULS, U))
      } else if (ctx.options.flag(CompilationFlag.UseYForStack)) {
        List(
          MLine.indexedS(LEAS, ctx.function.stackVariablesSize),
          MLine.pp(PULS, Y))
      } else {
        List(MLine.indexedS(LEAS, ctx.function.stackVariablesSize))
      }
    }
    val code: (List[MLine], List[MLine]) = statement match {
      case ReturnStatement(None) =>
        // TODO: clean stack
        // TODO: RTI
        (epilogue :+ MLine.inherent(RTS)) -> Nil
      case ReturnStatement(Some(e)) =>
        // TODO: clean stack
        // TODO: RTI
        AbstractExpressionCompiler.checkAssignmentType(ctx, e, ctx.function.returnType)
        val rts = List(MLine.inherent(RTS))
        val eval = ctx.function.returnType match {
          case FatBooleanType =>
            M6809ExpressionCompiler.compileToFatBooleanInB(ctx, e)
          case _ =>
            ctx.function.returnType.size match {
              case 0 =>
                ctx.log.error("Cannot return anything from a void function", statement.position)
                M6809ExpressionCompiler.compile(ctx, e, MExpressionTarget.NOTHING)
              case 1 => M6809ExpressionCompiler.compileToB(ctx, e)
              case 2 => M6809ExpressionCompiler.compileToD(ctx, e)
              case _ =>
                if (ctx.function.hasElidedReturnVariable) {
                  Nil
                } else {
                  M6809LargeBuiltins.storeLarge(ctx, VariableExpression(ctx.function.name + ".return"), e)
                }
            }
        }
        (eval ++ epilogue ++ rts) -> Nil
      case M6809AssemblyStatement(opcode, addrMode, expression, elidability) =>
        ctx.env.evalForAsm(expression, opcode) match {
          case Some(e) => List(MLine(opcode, addrMode, e, elidability)) -> Nil
          case None =>
            println(statement)
            ???
        }
      case Assignment(destination, source) =>
        if (destination == BlackHoleExpression) return M6809ExpressionCompiler.compile(ctx, source, MExpressionTarget.NOTHING, BranchSpec.None) -> Nil
        val destinationType = AbstractExpressionCompiler.getExpressionType(ctx, destination)
        val sourceType = AbstractExpressionCompiler.getExpressionType(ctx, source)
        AbstractExpressionCompiler.checkAssignmentType(ctx, source, destinationType)
        (destinationType.size match {
          case 0 => sourceType match {
            case _: ConstantBooleanType =>
              M6809ExpressionCompiler.compileToB(ctx, source) ++ M6809ExpressionCompiler.storeB(ctx, destination)
            case _: BooleanType =>
              M6809ExpressionCompiler.compileToFatBooleanInB(ctx, source) ++ M6809ExpressionCompiler.storeB(ctx, destination)
            case _ =>
              ctx.log.error("Cannot assign a void expression", statement.position)
              M6809ExpressionCompiler.compile(ctx, source, MExpressionTarget.NOTHING, BranchSpec.None) ++
                M6809ExpressionCompiler.compile(ctx, destination, MExpressionTarget.NOTHING, BranchSpec.None)
          }
          case 1 => sourceType match {
            case _: BooleanType =>
              M6809ExpressionCompiler.compileToFatBooleanInB(ctx, source) ++ M6809ExpressionCompiler.storeB(ctx, destination)
            case _ => M6809ExpressionCompiler.compileToB(ctx, source) ++ M6809ExpressionCompiler.storeB(ctx, destination)
          }
          case 2 => M6809ExpressionCompiler.compileToD(ctx, source) ++ M6809ExpressionCompiler.storeD(ctx, destination)
          case _ => M6809LargeBuiltins.storeLarge(ctx, destination, source)
        }) -> Nil
      case ExpressionStatement(expression) =>
        M6809ExpressionCompiler.compile(ctx, expression, MExpressionTarget.NOTHING) -> Nil
      case s:IfStatement =>
        compileIfStatement(ctx, s)
      case s:WhileStatement =>
        compileWhileStatement(ctx, s)
      case s:DoWhileStatement =>
        compileDoWhileStatement(ctx, s)
      case s:ForStatement =>
        compileForStatement(ctx, s)
      case s:MemsetStatement =>
        compile(ctx, s.original.get)
      case s:ForEachStatement =>
        compileForEachStatement(ctx, s)
      case s:BreakStatement =>
        compileBreakStatement(ctx, s) -> Nil
      case s:ContinueStatement =>
        compileContinueStatement(ctx, s) -> Nil
      case M6809AssemblyStatement(opcode, addrMode, expression, elidability) =>
        ctx.env.evalForAsm(expression, opcode) match {
          case Some(param) =>
            List(MLine(opcode, addrMode, param, elidability)) -> Nil
          case None =>
            ctx.log.error("Invalid parameter", expression.position)
            Nil -> Nil
        }
      case s:LabelStatement =>
        List(MLine.label(env.prefix + s.name)) -> Nil
      case s: GotoStatement =>
        env.eval(s.target) match {
          case Some(e) => List(MLine.absolute(JMP, e)) -> Nil
          case None =>
            (M6809ExpressionCompiler.compileToX(ctx, s.target) :+ MLine.indexedX(JMP, 0)) -> Nil
        }
      case s: ReturnDispatchStatement =>
        M6809ReturnDispatch.compile(ctx, s) -> Nil
      case EmptyStatement(_) =>
        Nil -> Nil
      case _ =>
        println(statement)
        ctx.log.error("Not implemented yet", statement.position)
        Nil -> Nil
    }
    code._1.map(_.positionIfEmpty(statement.position)) -> code._2.map(_.positionIfEmpty(statement.position))
  }

  override def labelChunk(labelName: String): List[MLine] = List(MLine(LABEL, NonExistent, Label(labelName).toAddress))

  override def jmpChunk(label: Label): List[MLine] = List(MLine.absolute(JMP, label.toAddress))

  override def branchChunk(opcode: BranchingOpcodeMapping, labelName: String): List[MLine] =
    List(MLine.shortBranch(opcode.m6809, labelName)) // TODO: ???

  override def compileExpressionForBranching(ctx: CompilationContext, expr: Expression, branching: BranchSpec): List[MLine] =
    if (AbstractExpressionCompiler.getExpressionType(ctx, expr) == FatBooleanType) {
      val prepareB = M6809ExpressionCompiler.compile(ctx, expr, MExpressionTarget.B, branching)
      if (M6809ExpressionCompiler.areNZFlagsBasedOnB(prepareB)) prepareB
      else prepareB :+ MLine.immediate(CMPB, 0)
    } else {
      M6809ExpressionCompiler.compile(ctx, expr, MExpressionTarget.NOTHING, branching)
    }

  override def replaceLabel(ctx: CompilationContext, line: MLine, from: String, to: String): MLine = line.parameter match {
      case MemoryAddressConstant(Label(l)) if l == from => line.copy(parameter = MemoryAddressConstant(Label(to)))
      case StructureConstant(s, List(z, MemoryAddressConstant(Label(l)))) if l == from => line.copy(parameter = StructureConstant(s, List(z, MemoryAddressConstant(Label(to)))))
      case _ => line
    }

  override def returnAssemblyStatement: ExecutableStatement = M6809AssemblyStatement(opcode = MOpcode.RTS, Inherent, LiteralExpression(0, 1), Elidability.Elidable )

  override def callChunk(label: ThingInMemory): List[MLine] = List(MLine.absolute(JSR, label.toAddress))

  override def areBlocksLarge(blocks: List[MLine]*): Boolean = true // TODO
}
