/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
package com.ibm.jikesrvm.opt;

import com.ibm.jikesrvm.opt.ir.*;
import java.util.*;

/**
 * A node in the LST (Loop Structure Tree)
 * 
 * @author Dave Grove
 * @author Mauricio J. Serrano
 */
class OPT_LSTNode extends OPT_SpaceEffGraphNode {

  /**
   * Basic block which is the loop head
   */
  OPT_BasicBlock header;
  
  /**
   * Basic blocks in the loop
   */
  OPT_BitVector loop;

  /**
   * The depth of the loop
   */
  int depth;

  /**
   * If the loop is entered from the loopHeader x times,
   * then the loopHead is executed loopMultiplier * x times.
   */
  float loopMultiplier;

  /**
   * The CFG Edges that are exits from the loop.
   */
  ArrayList loopExits;


  OPT_LSTNode(OPT_BasicBlock bb) {
    header = bb;
  }

  /**
   * Copy constructor
   *
   * @param node for copying
   */
  protected OPT_LSTNode(OPT_LSTNode node) {
    header         = node.header;
    loop           = node.loop;
    depth          = node.depth;
    loopMultiplier = node.loopMultiplier;
    loopExits      = node.loopExits;
  }

  OPT_BasicBlock getHeader() {
    return header;
  }

  OPT_BitVector getLoop() {
    return loop;
  }

  public String toString() {
    String tab = "";
    for (int i=0; i<depth; i++) {
      tab += "\t";
    }
    return tab + header + " " + loop + " "+loopExits+"\n";
  }

  public OPT_LSTNode getParent() { return (OPT_LSTNode)inNodes().next(); }

  public Enumeration getChildren() {
    return new Enumeration() {
        private OPT_SpaceEffGraphEdge _edge = _outEdgeStart;
        public boolean hasMoreElements() { return _edge != null; }
        public Object nextElement()      {
          OPT_SpaceEffGraphEdge e = _edge;
          _edge = e.nextOut;
          return e.toNode();
        }
      };
  }

  public void initializeLoopExits() {
    loopExits = new ArrayList();
  }

  public void addLoopExit(OPT_BasicBlock source, OPT_BasicBlock target, float prob) {
    loopExits.add(new Edge(source, target, prob));
  }

  static final class Edge {
    OPT_BasicBlock source;
    OPT_BasicBlock target;
    float probability;

    Edge(OPT_BasicBlock s, OPT_BasicBlock t, float p) {
      source = s;
      target = t;
      probability = p;
    }
    public String toString() {
      return source + "->" + target + " prob = "+probability;
    }
  }

}


