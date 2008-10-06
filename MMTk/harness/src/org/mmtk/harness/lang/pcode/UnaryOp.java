/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.harness.lang.pcode;

import org.mmtk.harness.lang.compiler.Register;

public abstract class UnaryOp extends PseudoOp {

  protected final int operand;

  public UnaryOp(String name, Register resultTemp, Register operand) {
    super(1, name, resultTemp);
    this.operand = operand.getIndex();
  }

  public UnaryOp(String name, Register operand) {
    super(1, name);
    this.operand = operand.getIndex();
  }

  public String toString() {
    return String.format("%s(%s)", super.toString(), Register.nameOf(operand));
  }
}