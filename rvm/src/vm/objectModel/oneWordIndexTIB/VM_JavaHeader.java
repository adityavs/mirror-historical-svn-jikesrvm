/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

//-#if RVM_WITH_OPT_COMPILER
import instructionFormats.*;
//-#endif

/**
 * Defines the JavaHeader portion of the object header for the 
 * JikesRVM object model. <p>
 * This object model uses a one-word header for most scalar objects, and
 * a two-word header for scalar objects of classes with synchronized
 * methods<p>
 *
 * In this object model, the bottom N bits of the TIB word are the
 * available bits, and the rest of the TIB word holds the index into the
 * JTOC holding the TIB reference.
 *
 * @see VM_NurseryObjectModel
 *
 * @author David Bacon
 * @author Steve Fink
 * @author Dave Grove
 */
public final class VM_JavaHeader extends VM_NurseryObjectModel 
  implements VM_Uninterruptible,
	     VM_BaselineConstants
	     //-#if RVM_WITH_OPT_COMPILER
	     ,OPT_Operators
	     //-#endif
{
  

  /**
   * How many bits the TIB index is shifted in the header.
   * NOTE: when this is 2 then we have slightly more efficient access
   * to the TIB, since the shifted TIB index its JTOC offset.
   */
  private static final int TIB_SHIFT = NUM_AVAILABLE_BITS;
  
  /**
   * Mask for available bits
   */  
  private static final int AVAILABLE_BITS_MASK = ~(0xffffffff << NUM_AVAILABLE_BITS);

  /**
   * Mask to extract the TIB index
   */
  private static final int TIB_MASK = ~(AVAILABLE_BITS_MASK | HASH_STATE_MASK);

  static {
    if (VM.VerifyAssertions) {
      VM.assert(VM_MiscHeader.REQUESTED_BITS + VM_AllocatorHeader.REQUESTED_BITS <= NUM_AVAILABLE_BITS);
    }
  }

  /**
   * Get the TIB for an object.
   */
  public static Object[] getTIB(Object o) { 
    int tibWord = VM_Magic.getIntAtOffset(o,TIB_OFFSET);
    if (VM_Collector.MOVES_OBJECTS) {
      int fmask = tibWord & VM_AllocatorHeader.GC_FORWARDING_MASK;
      if (fmask != 0 && fmask == VM_AllocatorHeader.GC_FORWARDED) {
	int forwardPtr = tibWord & ~VM_AllocatorHeader.GC_FORWARDING_MASK;
	tibWord = VM_Magic.getIntAtOffset(VM_Magic.addressAsObject(forwardPtr), TIB_OFFSET);
      }
    }      
    int offset = (tibWord & TIB_MASK) >>> (TIB_SHIFT - 2);
    return VM_Magic.addressAsObjectArray(VM_Magic.getMemoryWord(VM_Magic.getTocPointer() + offset));
  }
  
  /**
   * Set the TIB for an object.
   */
  public static void setTIB(Object ref, Object[] tib) {
    VM_Magic.pragmaInline();
    int idx = VM_Magic.objectAsType(tib[0]).getTibSlot() << TIB_SHIFT;
    if (VM.VerifyAssertions) VM.assert((idx & TIB_MASK) == idx);
    int tibWord = (VM_Magic.getIntAtOffset(ref, TIB_OFFSET) & ~TIB_MASK) | idx;
    VM_Magic.setIntAtOffset(ref, TIB_OFFSET, tibWord);

  }

  /**
   * Set the TIB for an object.
   * Note: Beware; this function clears the additional bits.
   */
  public static void setTIB(BootImageInterface bootImage, int refOffset, int tibAddr, VM_Type type) {
    int idx = type.getTibSlot() << TIB_SHIFT;
    if (VM.VerifyAssertions) VM.assert((idx & TIB_MASK) == idx);
    bootImage.setAddressWord(refOffset + TIB_OFFSET, idx);
  }

  /**
   * Process the TIB field during copyingGC.
   */
  public static void gcProcessTIB(int ref) {
    // nothing to do (TIB is not a pointer)
  }

  /**
   * Get a reference to the TIB for an object.
   *
   * @param jdpService
   * @param address address of the object
   */
  public static ADDRESS getTIB(JDPServiceInterface jdpService, ADDRESS ptr) {
    int tibWord = jdpService.readMemory(ptr + TIB_OFFSET);
    if (VM_Collector.MOVES_OBJECTS) {
      int fmask = tibWord & VM_AllocatorHeader.GC_FORWARDING_MASK;
      if (fmask != 0 && fmask == VM_AllocatorHeader.GC_FORWARDED) {
	int forwardPtr = tibWord & ~VM_AllocatorHeader.GC_FORWARDING_MASK;
	tibWord = jdpService.readMemory(forwardPtr + TIB_OFFSET);
      }
    }      
    int index = (tibWord & TIB_MASK) >>> TIB_SHIFT;
    return jdpService.readJTOCSlot(index);
  }

  /**
   * The following method will emit code that moves a reference to an
   * object's TIB into a destination register.
   * DANGER: this function destroys R0 if VM_Collector.MOVES_OBJECTS !!!!
   *         
   * @param asm the assembler object to emit code with
   * @param dest the number of the destination register
   * @param object the number of the register holding the object reference
   */
  //-#if RVM_FOR_POWERPC
  public static void baselineEmitLoadTIB(VM_Assembler asm, int dest, 
                                         int object) {
    if (VM.VerifyAssertions) VM.assert(TIB_SHIFT == 2);
    int ME = 31 - TIB_SHIFT;
    int MB = HASH_STATE_BITS;
    if (VM_Collector.MOVES_OBJECTS) {
      if (VM.VerifyAssertions) {
	VM.assert(dest != 0);
	VM.assert(VM_AllocatorHeader.GC_FORWARDING_MASK == 0x00000003);
	VM.assert(VM_AllocatorHeader.GC_FORWARDED != VM_Collector.MARK_VALUE);
      }
      // The collector may have laid down a forwarding pointer 
      // in place of the TIB word.  Check for this fringe case
      // and handle it by following the forwarding pointer to
      // find the TIB.
      asm.emitL   (dest, TIB_OFFSET, object);
      asm.emitANDI(0, dest, VM_AllocatorHeader.GC_FORWARDING_MASK);
      asm.emitBEQ (5);  // if dest & FORWARDING_MASK == 0; then dest has a valid tib index
      asm.emitCMPI(0, VM_AllocatorHeader.GC_FORWARDED);
      // Two cases: (1) the pointer is forwarded or being forwarded
      //            (2) the pointer is to a bootimage object that has been marked
      asm.emitBNE (3); 
      // It really has been forwarded; chase the forwarding pointer and get the tib word from there.
      asm.emitRLWINM(dest, dest, 0, 0, 29);    // mask out bottom two bits of forwarding pointer
      asm.emitL     (dest, TIB_OFFSET, dest); // get TIB word from forwarded object
      // The following clears the high and low-order bits. See p.119 of PowerPC book
      // Because TIB_SHIFT is 2 the masked value is a JTOC offset.
      asm.emitRLWINM(dest, dest, 0, MB, ME);
      asm.emitLX(dest,JTOC,dest);
    } else {
      asm.emitL(dest, TIB_OFFSET, object);
      // The following clears the high and low-order bits. See p.119 of PowerPC book
      // Because TIB_SHIFT is 2 the masked value is a JTOC offset.
      asm.emitRLWINM(dest, dest, 0, MB, ME);
      asm.emitLX(dest,JTOC,dest);
    }
  }
  //-#elif RVM_FOR_IA32
  public static void baselineEmitLoadTIB(VM_Assembler asm, byte dest, 
                                         byte object) {
    VM.assert(false, "update for forwarding ptrs!");
    if (VM.VerifyAssertions) VM.assert(TIB_SHIFT == 2);
    asm.emitMOV_Reg_RegDisp(dest, object, TIB_OFFSET);
    asm.emitAND_Reg_Imm(dest,TIB_MASK);
    // Because TIB_SHIFT is 2 the masked value is a JTOC offset.
    asm.emitMOV_Reg_RegDisp(dest,JTOC,dest);
  }
  /**
   * The following method will emit code that pushes a reference to an
   * object's TIB onto the stack.
   *
   * DANGER, DANGER!!! This method kills the value in the 'object'
   * register.
   *
   * TODO: consider deprecating this method; rewriting the appropriate
   * sequences in the baseline compiler to use a scratch register.
   *
   * @param asm the assembler object to emit code with
   * @param object the number of the register holding the object reference
   */
  public static void baselineEmitPushTIB(VM_Assembler asm, byte object) {
    baselineEmitLoadTIB(asm,object,object);
    asm.emitPUSH_Reg(object);
  }
  //-#endif

  //-#if RVM_WITH_OPT_COMPILER
  /**
   * Mutate a GET_OBJ_TIB instruction to the LIR
   * instructions required to implement it.
   * 
   * @param s the GET_OBJ_TIB instruction to lower
   * @param ir the enclosing OPT_IR
   */
  public static void lowerGET_OBJ_TIB(OPT_Instruction s, OPT_IR ir) {
    OPT_RegisterOperand result = GuardedUnary.getClearResult(s);
    OPT_RegisterOperand ref = GuardedUnary.getClearVal(s).asRegister();
    OPT_Operand guard = GuardedUnary.getClearGuard(s);
    OPT_RegisterOperand headerWord = OPT_ConvertToLowLevelIR.InsertLoadOffset(s, ir, INT_LOAD, 
                                                                              VM_Type.IntType, ref, TIB_OFFSET, guard);
    OPT_RegisterOperand tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_AND, 
                                                                         VM_Type.IntType, headerWord, 
                                                                         new OPT_IntConstantOperand(TIB_MASK));
    // shift the tibIdx to a byte offset.
    if (TIB_SHIFT > 2) {
      tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_USHR, VM_Type.IntType, tibOffset, 
                                                       new OPT_IntConstantOperand(TIB_SHIFT- 2));
    } else if (TIB_SHIFT < 2) {
      tibOffset = OPT_ConvertToLowLevelIR.InsertBinary(s, ir, INT_SHL, VM_Type.IntType, tibOffset, 
                                                       new OPT_IntConstantOperand(2 - TIB_SHIFT));
    }

    Load.mutate(s, INT_LOAD, result, ir.regpool.makeJTOCOp(ir,s), tibOffset, null);
  }

  //-#endif
}
