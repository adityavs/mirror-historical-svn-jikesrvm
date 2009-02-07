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
package org.mmtk.harness.lang.ast;

import java.util.Collections;
import java.util.List;

import org.mmtk.harness.lang.Visitor;
import org.mmtk.harness.lang.parser.Token;

/**
 * Prints the value of an expression
 */
public class PrintStatement extends AbstractAST implements Statement {
  /** The expression to print */
  private final List<Expression> exprs;

  /**
   * Constructor
   * @param slot Stack frame slot of the variable
   */
  public PrintStatement(Token t, List<Expression> exprs) {
    super(t);
    this.exprs = exprs;
  }

  public void accept(Visitor v) {
    v.visit(this);
  }
  public List<Expression> getArgs() { return Collections.unmodifiableList(exprs); }
}
