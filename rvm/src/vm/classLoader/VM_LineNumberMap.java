/*
 * (C) Copyright IBM Corp. 2001
 */

/**
 *  A java method's source line number information.
 */
class VM_LineNumberMap {
  //----------------//
  // Implementation //
  //----------------//

   //!!TODO: some of these arrays could be short[] because methods are limited to 64k bytecodes.
   
   // Note that line mappings for a method appear in order of increasing bytecode offset.
   // The same line number can appear more than once (each with a different bytecode offset).

  int[] startPCs;                // bytecode offset at which each instruction sequence begins
  // 0-indexed from start of method's bytecodes[]

  int[] lineNumbers;             // line number at which each instruction sequence begins
  // 1-indexed from start of method's source file
   
  VM_LineNumberMap(int n) {
    startPCs    = new int[n];
    lineNumbers = new int[n];
  }

  VM_LineNumberMap(VM_BinaryData input, int n) {
    this(n);
    for (int i = 0; i < n; ++i) {
      startPCs[i]    = input.readUnsignedShort();
      lineNumbers[i] = input.readUnsignedShort();
    }
  }

  // Return the line number information for the argument bytecode index.
  final int getLineNumberForBCIndex(int bci) {
    int idx;
    for (idx = 0; idx < startPCs.length; idx++) {
      if (bci < startPCs[idx]) {
	if (idx == 0) idx++; // add 1, so we can subtract 1 below.
	break;
      }
    }
    return lineNumbers[--idx];
  }

}
