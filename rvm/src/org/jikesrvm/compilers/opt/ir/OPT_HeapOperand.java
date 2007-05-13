/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
package org.jikesrvm.compilers.opt.ir;

import org.jikesrvm.compilers.opt.OPT_HeapVariable;

/**
 * Represents a heap variable for instructions in Heap Array SSA form.
 * 
 * @see OPT_Operand
 * @see OPT_HeapVariable
 */
public final class OPT_HeapOperand<T> extends OPT_Operand {

  /**
   * The heap variable corresponding to this operand.
   */
  public final OPT_HeapVariable<T> value;

  /**
   * Return  the heap variable corresponding to this operand.
   * @return the heap variable corresponding to this operand.
   */
  public OPT_HeapVariable<T> getHeapVariable() {
    return value;
  }

  /**
   * Return the number of the heap variable corresponding to this
   * operand.
   * @return the number of the heap variable corresponding to this
   * operand. 
   */
  public int getNumber() {
    return value.getNumber();
  }

  /**
   * Return the type corresponding to the heap variable associated with
   * this operand.
   * @return the type corresponding to the heap variable associated with
   * this operand.
   */
  public T getHeapType() {
    return value.getHeapType();
  }

  /**
   * Construct an operand corresponding to a heap variable.
   * @param   heap the heap variable corresponding to this operand.
   */
  public OPT_HeapOperand(OPT_HeapVariable<T> heap) {
    value = heap;
  }

  /**
   * Construct a new heap operand associated with the same heap variable as
   * this operand
   * 
   * @return a new heap operand associated with the same heap variable as
   * this operand
   */
  public OPT_HeapOperand<T> copy() {
    return new OPT_HeapOperand<T>(value);
  }

  /**
   * Does this operand correspond to the same heap variable as another
   * heap operand?
   *
   * @param op the second operand to compare with
   * @return true or false
   */
  public boolean similar(OPT_Operand op) {
    if (!(op instanceof OPT_HeapOperand<?>))
      return  false;
    OPT_HeapOperand<?> h = (OPT_HeapOperand<?>)op;
    return  (h.value == value);
  }

  /**
   * Return a string representation of this operand.
   * @return a string representation of this operand.
   */
  public String toString() {
    return  value.toString();
  }

  /**
   * Associate this operand with a given instruction.
   * @param s the associated instruction
   */
  public void setInstruction(OPT_Instruction s) {
    this.instruction = s;
  }

  /**
   * Return the instruction associated with this operand.
   * @return the instruction associated with this operand.
   */
  public OPT_Instruction getInstruction() {
    return  instruction;
  }
}