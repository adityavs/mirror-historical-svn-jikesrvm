/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

import instructionFormats.*;
import java.util.Enumeration;

/**
 * This abstract class contains a bunch of useful static methods for
 * performing operations on IR.
 *
 * All functions defined here should (1) not be specific to the MIR and
 * (2) not be specific to Jalapeno.  Any Jalapeno-specific or MIR specific 
 * helper functions should be declared on OPT_JalapenoIRTools.
 * 
 * @author Jong-Deok Choi
 * @author Dave Grove
 * @author Mauricio Serrano
 * @author John Whaley
 */
abstract class OPT_IRTools implements OPT_Operators, VM_Constants {

  /**
   * Create an integer register operand for a given register.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Load.create(INT_LOAD, R(r2), R(r1), I(4)) ...
   * </pre>
   *
   * @param reg the given register
   * @return integer register operand
   */
  static final OPT_RegisterOperand R(OPT_Register reg) {
    return new OPT_RegisterOperand(reg, VM_Type.IntType);
  }

  /**
   * Create a float register operand for a given register.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Load.create(FLOAT_LOAD, F(r2), R(r1), I(4)) ...
   * </pre>
   *
   * @param reg the given register
   * @return float register operand
   */
  static final OPT_RegisterOperand F(OPT_Register reg) {
    return new OPT_RegisterOperand(reg, VM_Type.FloatType);
  }

  /**
   * Create a double register operand for a given register.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Load.create(DOUBLE_LOAD, D(r2), R(r1), I(4)) ...
   * </pre>
   *
   * @param reg the given register
   * @return double register operand
   */
  static final OPT_RegisterOperand D(OPT_Register reg) {
    return new OPT_RegisterOperand(reg, VM_Type.DoubleType);
  }

  /**
   * Create a long register operand for a given register.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Binary.create(LONG_LOAD, L(r2), R(r1), I(4)) ...
   * </pre>
   *
   * @param reg the given register
   * @return long register operand
   */
  static final OPT_RegisterOperand L(OPT_Register reg) {
    return new OPT_RegisterOperand(reg, VM_Type.LongType);
  }

  /**
   * Create a condition register operand for a given register.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Binary.create(INT_CMP, CR(c2), R(r1), I(4)) ...
   * </pre>
   *
   * @param reg the given register
   * @return condition register operand
   */
  static final OPT_RegisterOperand CR(OPT_Register reg) {
    return new OPT_RegisterOperand(reg, VM_Type.IntType);
  }

  /**
   * Create an integer constant operand with a given value.
   * To be used in passthrough expressions like
   * <pre>
   *    ... Load.create(INT_LOAD, R(r2), R(r1), I(4)) ...
   * </pre>
   *
   * @param value, the int constant
   * @return integer constant operand
   */
  static final OPT_IntConstantOperand I(int value) {
    return new OPT_IntConstantOperand(value);
  }

  /**
   * Create a long constant operand with a given value.
   * To be used in passthrough expressions like
   * <pre>
   *    ...<op>.create(...., LC(0L) ...
   * </pre>
   *
   * @param value the long value
   * @return long constant operand
   */
  static final OPT_LongConstantOperand LC(long value) {
    return new OPT_LongConstantOperand(value);
  }

  /**
   * Create a long constant operand with a given value.
   * To be used in passthrough expressions like
   * <pre>
   *    ...<op>.create(...., FC(0L) ...
   * </pre>
   *
   * @param value the float value
   * @return float constant operand
   */
  static final OPT_FloatConstantOperand FC(float value) {
    return new OPT_FloatConstantOperand(value);
  }

  /**
   * Create a long constant operand with a given value.
   * To be used in passthrough expressions like
   * <pre>
   *    ...<op>.create(...., DC(0L) ...
   * </pre>
   *
   * @param value the double value
   * @return double constant operand
   */
  static final OPT_DoubleConstantOperand DC(double value) {
    return new OPT_DoubleConstantOperand(value);
  }

  /**
   * Create a new OPT_TrueGuardOperand.
   * To be used in passthrough expressions like
   * <pre>
   *    ...<op>.create(...., TG() ...
   * </pre>
   * 
   * @return true guard operand
   */
  static final OPT_TrueGuardOperand TG() {
    return new OPT_TrueGuardOperand();
  }


  /**
   * Copy the position information from the source instruction to
   * the destination instruction, returning the source instruction.
   * To be used in passthrough expressions like
   * <pre>
   *    instr.insertBack(CPOS(instr, Load.create(...)));
   * </pre>
   *
   * @param src the instruction to copy position information from
   * @param dst the instruction to copy position information to
   * @return dest
   */
  static final OPT_Instruction CPOS(OPT_Instruction src, 
				    OPT_Instruction dst) {
    dst.copyPosition(src);
    return dst;
  }

  /**
   * Returns a constant operand with a default value for a given type
   *
   * @param type desired type
   * @return a constant operand with the default value for type
   */
  static final OPT_Operand getDefaultOperand(VM_Type type) {
    if (type.isBooleanType()) return new OPT_IntConstantOperand(0);
    if (type.isByteType())    return new OPT_IntConstantOperand(0);
    if (type.isCharType())    return new OPT_IntConstantOperand(0);
    if (type.isIntType())     return new OPT_IntConstantOperand(0);
    if (type.isShortType())   return new OPT_IntConstantOperand(0);
    if (type.isLongType())    return new OPT_LongConstantOperand(0);
    if (type.isFloatType())   return new OPT_FloatConstantOperand(0f);
    if (type.isDoubleType())  return new OPT_DoubleConstantOperand(0.0);
    return new OPT_NullConstantOperand();
  }

  /**
   * Returns the correct operator for moving the given data type.
   *
   * @param type desired type to move
   * @param isLIR do we want a LIR operator?
   * @return the OPT_Operator to use for moving a value of the given type
   */
  static final OPT_Operator getMoveOp(VM_Type type, boolean isLIR) {
    if (type.isLongType())    return LONG_MOVE;
    if (type.isFloatType())   return FLOAT_MOVE;
    if (type.isDoubleType())  return DOUBLE_MOVE;
    if (type == OPT_ClassLoaderProxy.VALIDATION_TYPE) return GUARD_MOVE;
    if (!isLIR && type.isReferenceType()) {
      return REF_MOVE;
    }
    return INT_MOVE;
  }


  /**
   * Returns the correct operator for loading from the given field
   *
   * @param field field to load from
   * @param isLIR do we want a LIR operator?
   * @return the OPT_Operator to use when loading the given field
   */
  static final OPT_Operator getLoadOp(VM_Field field, boolean isLIR) {
    VM_Type type = field.getType();
    // TODO: Actually pack subword fields and then use these operators
    //       on PPC (a Big endian machine) too!
    //-#if RVM_FOR_IA32
    if (type.isByteType())      return BYTE_LOAD;
    if (type.isBooleanType())   return UBYTE_LOAD;
    if (type.isCharType())      return USHORT_LOAD;
    if (type.isShortType())     return SHORT_LOAD;
    //-#endif
    if (type.isLongType())      return LONG_LOAD;
    if (type.isFloatType())     return FLOAT_LOAD;
    if (type.isDoubleType())    return DOUBLE_LOAD;
    if (!isLIR && type.isReferenceType()) {
      return REF_LOAD;
    }
    return INT_LOAD;
  }

  /**
   * Returns the correct operator for storing to the given field.
   *
   * @param type desired type to store
   * @param isLIR do we want a LIR operator?
   * @return the OPT_Operator to use when storing to the given field
   */
  static final OPT_Operator getStoreOp(VM_Field field, boolean isLIR) {
    VM_Type type = field.getType();
    // TODO: Actually pack subword fields and then use these operators
    //       on PPC (a Big endian machine) too!
    //-#if RVM_FOR_IA32
    if (type.isByteType())      return BYTE_STORE;
    if (type.isBooleanType())   return BYTE_STORE;
    if (type.isCharType())      return SHORT_STORE;
    if (type.isShortType())     return SHORT_STORE;
    //-#endif
    if (type.isLongType())       return LONG_STORE;
    if (type.isFloatType())      return FLOAT_STORE;
    if (type.isDoubleType())     return DOUBLE_STORE;
    if (!isLIR && type.isReferenceType()) {
      return REF_STORE;
    }
    return INT_STORE;
  }


  /**
   * Generates an instruction to move the given operand into a register, and
   * inserts it before the given instruction.
   *
   * @param pool register pool to allocate from
   * @param s instruction to insert before
   * @param op operand to copy to a register
   * @param isLIR generate an LIR instruction?
   * @return register operand that we copied into
   */
  static final OPT_RegisterOperand moveIntoRegister(OPT_RegisterPool pool,
						    OPT_Instruction s,
						    OPT_Operand op,
						    boolean isLIR) {
    if (op instanceof OPT_RegisterOperand) {
      return (OPT_RegisterOperand) op;
    }
    VM_Type type = op.getType();
    OPT_Operator move_op = OPT_IRTools.getMoveOp(type,isLIR);
    return moveIntoRegister(type, move_op, pool, s, op);
  }


  /**
   * Generates an instruction to move the given operand into a register, and
   * inserts it before the given instruction.
   *
   * @param type type to move
   * @param move_op move operator to use
   * @param pool register pool to allocate from
   * @param s instruction to insert before
   * @param op operand to copy to a register
   * @return last use register operand that we copied into
   */
  static final OPT_RegisterOperand moveIntoRegister(VM_Type type,
						    OPT_Operator move_op,
						    OPT_RegisterPool pool,
						    OPT_Instruction s,
						    OPT_Operand op) {
    OPT_RegisterOperand rop = pool.makeTemp(type);
    s.insertBack(Move.create(move_op, rop, op));
    rop = rop.copyD2U();
    return rop;
  }


  /**
   * Moves the 'from' instruction to immediately before the 'to' instruction.
   *
   * @param from instruction to move
   * @param to instruction after where you want it moved
   */
  static final void moveInstruction(OPT_Instruction from, OPT_Instruction to) {
    from.remove();
    to.insertBack(from);
  }


  /**
   * Inserts the instructions in the given basic block after the given
   * instruction.
   *
   * @param after instruction after where you want it inserted
   * @param temp basic block which contains the instructions to be inserted.
   */
  static final void insertInstructionsAfter(OPT_Instruction after,
					    OPT_BasicBlock temp) {
    if (temp.isEmpty()) return;
    OPT_Instruction after_after = after.getNext();
    after.linkWithNext(temp.firstRealInstruction());
    if (after_after == null) {
      temp.lastRealInstruction().setNext(null);
    } else {
      temp.lastRealInstruction().linkWithNext(after_after);
    }
  }
  /**
   * Make an empty basic block on an edge in the control flow graph,
   * and fix up the control flow graph and IR instructions accordingly.
   *
   * This routine will create the control struture
   * <pre>
   * in -> bb -> out.
   * </pre>
   * <em> Precondition </em>: There is an edge in the control flow graph 
   * from * in -> out.
   *
   * <p> TODO: write a cleaner version of this and put it in OPT_IRTools.java
   * Once we explicitly link phi operands to basic blocks, we won't have
   * to muck with the internals of the control flow graph to implement
   * this function.
   *
   * @param in the source of the control flow edge
   * @param out the sink of the control flow edge
   * @param ir the governing IR
   * @return the new basic block bb
   */
  public static OPT_BasicBlock makeBlockOnEdge (OPT_BasicBlock in, 
                                                OPT_BasicBlock out, 
                                                OPT_IR ir) {
    // 1. Create the new basic block
    OPT_BasicBlock bb = in.createSubBlock(-1, ir);
    
    // 2. Splice the new basic block into the code order
    OPT_BasicBlock next = in.nextBasicBlockInCodeOrder();
    if (next == null ) {
      ir.cfg.addLastInCodeOrder(bb);
    } else {
      ir.cfg.breakCodeOrder(in, next);
      ir.cfg.linkInCodeOrder(in, bb);
      ir.cfg.linkInCodeOrder(bb, next);
    }
  
    // 3. update in's branch instructions
    boolean foundGoto = false;
    OPT_BranchOperand target = bb.makeJumpTarget();
    OPT_BranchOperand outTarget = out.makeJumpTarget();
    for (OPT_InstructionEnumeration e = in.reverseRealInstrEnumerator(); 
        e.hasMoreElements();) {
      OPT_Instruction s = e.next();
      if (IfCmp2.conforms(s)) {
        if (IfCmp2.getTarget1(s).similar(outTarget))
          IfCmp2.setTarget1(s, (OPT_BranchOperand)target.copy());
        if (IfCmp2.getTarget2(s).similar(outTarget))
          IfCmp2.setTarget2(s, (OPT_BranchOperand)target.copy());
      } else if (IfCmp.conforms(s)) {
        if (IfCmp.getTarget(s).similar(outTarget)) {
          IfCmp.setTarget(s, (OPT_BranchOperand)target.copy());
        }
      } else if (MethodIfCmp.conforms(s)) {
        if (MethodIfCmp.getTarget(s).similar(outTarget))
          MethodIfCmp.setTarget(s, (OPT_BranchOperand)target.copy());
      } else if (TypeIfCmp.conforms(s)) {
        if (TypeIfCmp.getTarget(s).similar(outTarget))
          TypeIfCmp.setTarget(s, (OPT_BranchOperand)target.copy());
      } else if (Goto.conforms(s)) {
        foundGoto = true;
        if (Goto.getTarget(s).similar(outTarget))
          Goto.setTarget(s, (OPT_BranchOperand)target.copy());
      } else if (TableSwitch.conforms(s)) {
        foundGoto = true;
        if (TableSwitch.getDefault(s).similar(outTarget))
          TableSwitch.setDefault(s, (OPT_BranchOperand)target.copy());
        for (int i = 0; i < TableSwitch.getNumberOfTargets(s); i++)
          if (TableSwitch.getTarget(s, i).similar(outTarget))
            TableSwitch.setTarget(s, i, (OPT_BranchOperand)target.copy());
      } else if (LowTableSwitch.conforms(s)) {
        foundGoto = true;
        for (int i = 0; i < LowTableSwitch.getNumberOfTargets(s); i++)
          if (LowTableSwitch.getTarget(s, i).similar(outTarget))
            LowTableSwitch.setTarget(s, i, (OPT_BranchOperand)target.copy());
      } else if (LookupSwitch.conforms(s)) {
        foundGoto = true;
        if (LookupSwitch.getDefault(s).similar(outTarget))
          LookupSwitch.setDefault(s, (OPT_BranchOperand)target.copy());
        for (int i = 0; i < LookupSwitch.getNumberOfTargets(s); i++)
          if (LookupSwitch.getTarget(s, i).similar(outTarget))
            LookupSwitch.setTarget(s, i, (OPT_BranchOperand)target.copy());
      } else {
        // done processing all branches
        break;
      }
    }
    
    // 4. Add a goto bb->out 
    OPT_Instruction s = Goto.create(GOTO, out.makeJumpTarget());
    bb.appendInstruction(s);
    // add goto in->next
    // if out was not the fallthrough, add a GOTO to preserve this
    // control flow
    if (out != next) {
      // if there's already a GOTO, there's no fall through
      if (!foundGoto) {
        s = Goto.create(GOTO, next.makeJumpTarget());
        in.appendInstruction(s);
      }
    }

    // 5. Update the CFG
    in.recomputeNormalOut(ir);
    bb.recomputeNormalOut(ir);

    return  bb;
  }
  /**
   * Is the operand u, which is a use in instruction s, also a def
   * in instruction s?  That is, is this operand defined as a DU operand
   * in InstructionFormatList.dat.
   *
   * TODO!!: This implementation is slow.  Think about adding
   * some IR support for this functionality; possibly add methods like
   * enumeratePureDefs(), enumerateImpureUses(), etc ..., and restructure
   * the caller to avoid having to call this function.  Not going
   * to put effort into this now, as the whole scratch register
   * architecture has a questionable future.
   */
  static boolean useDoublesAsDef(OPT_Operand u, 
                                 OPT_Instruction s) {
    for (Enumeration d = s.getDefs(); d.hasMoreElements(); ) {
      OPT_Operand def = (OPT_Operand)d.nextElement();
      if (def != null) {
        if (def == u) return true;
      }
    }
    return false;
  }
  /**
   * Is the operand d, which is a def in instruction s, also a def
   * in instruction s?  That is, is this operand defined as a DU operand
   * in InstructionFormatList.dat.
   *
   * TODO!!: This implementation is slow.  Think about adding
   * some IR support for this functionality; possibly add methods like
   * enumeratePureDefs(), enumerateImpureUses(), etc ..., and restructure
   * the caller to avoid having to call this function.  Not going
   * to put effort into this now, as the whole scratch register
   * architecture has a questionable future.
   */
  static boolean defDoublesAsUse(OPT_Operand d, 
                                 OPT_Instruction s) {
    for (Enumeration u = s.getUses(); u.hasMoreElements(); ) {
      OPT_Operand use = (OPT_Operand)u.nextElement();
      if (use != null) {
        if (use.similar(d)) return true;
      }
    }
    return false;
  }

  /**
   * Does instruction s define register r?
   */
  static boolean definedIn(OPT_Register r, OPT_Instruction s) {
    for (Enumeration e = s.getDefs(); e.hasMoreElements(); ) {
      OPT_Operand op = (OPT_Operand)e.nextElement();
      if (op != null && op.isRegister()) {
        if (op.asRegister().register.number == r.number) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Does instruction s use register r?
   */
  static boolean usedIn(OPT_Register r, OPT_Instruction s) {
    for (Enumeration e = s.getUses(); e.hasMoreElements(); ) {
      OPT_Operand op = (OPT_Operand)e.nextElement();
      if (op != null && op.isRegister()) {
        if (op.asRegister().register.number == r.number) {
          return true;
        }
      }
    }
    return false;
  }
}

