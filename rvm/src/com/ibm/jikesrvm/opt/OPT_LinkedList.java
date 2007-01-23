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

import com.ibm.jikesrvm.VM;

/**
 * @author Mauricio J. Serrano
 * @author John Whaley
 */
public class OPT_LinkedList {
  
  public OPT_LinkedList() { }

  public OPT_LinkedList(OPT_LinkedListElement e) {
    start = end = e;
  }

  final public OPT_LinkedListElement first() {
    return start;
  }

  final public OPT_LinkedListElement last() {
    return  end;
  }

  /**
   * append at the end of the list
   */
  final public void append(OPT_LinkedListElement e) {
    if (e == null) return;
    if (end != null) {
      end.insertAfter(e);
    } else {
      if (VM.VerifyAssertions) VM._assert(start == null); // empty list!
      start = e;
    }
    end = e;
  }

  /**
   * insert at the start of the list
   */
  final public void prepend(OPT_LinkedListElement e) {
    if (start != null) {
      e.next = start;
    } else {      // empty list
      if (VM.VerifyAssertions) VM._assert(end == null); // empty list!
      end = e;
      e.next = null;
    }
    // in either case, e is the first node on the list
    start = e;
  }

  /**
   *  Insert into the list after the given element
   */
  final public void insertAfter(OPT_LinkedListElement old, OPT_LinkedListElement e) {
    old.insertAfter(e);
    if (old == end) {
      end = e;
      e.next = null;
    }
  }

  /**
   * removes the next element from the list
   */
  final public void removeNext(OPT_LinkedListElement e) {
    // update end if needed
    if (end == e.getNext())
      end = e;
    // remove the element
    e.next = e.getNext().getNext();
  }

  /**
   * remove an element from the list.
   */
  final public void remove(OPT_LinkedListElement e) {
    if (start == e) {
      removeHead();
    } else {
      if (start == null) return;
      OPT_LinkedListElement current = start;
      OPT_LinkedListElement next = start.next;
      while (next != null) {
        if (next == e) {
          removeNext(current);
          return;
        } else {
          current = next;
          next = current.next;
        }
      }
    }
  }

  /**
   * removes the head element from the list
   */
  final public OPT_LinkedListElement removeHead() {
    if (start == null)
      return  null;
    OPT_LinkedListElement result = start;
    start = result.next;
    if (start == null) end = null; // list is now empty
    result.next = null;
    return  result;
  }

  private OPT_LinkedListElement start;
  private OPT_LinkedListElement end;
}



