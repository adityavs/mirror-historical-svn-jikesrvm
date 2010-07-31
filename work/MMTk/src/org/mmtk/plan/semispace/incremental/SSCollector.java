/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.mmtk.plan.semispace.incremental;

import org.mmtk.plan.*;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.LargeObjectLocal;
import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.vm.VM;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

@Uninterruptible
public class SSCollector extends StopTheWorldCollector {

  /****************************************************************************
   * Instance fields
   */

  protected final SSTraceLocal trace;
  protected final CopyLocal ss;
  protected final LargeObjectLocal los;

  private static final PreGCToSpaceLinearSanityScan preGCSanity = new PreGCToSpaceLinearSanityScan();
  private static final PostGCToSpaceLinearSanityScan postGCSanity = new PostGCToSpaceLinearSanityScan();
  public static final ToSpaceLinearScanTrace linearTrace = new ToSpaceLinearScanTrace();

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Constructor
   */
  public SSCollector() {
    this(new SSTraceLocal(global().ssTrace));
  }

  /**
   * Constructor
   * @param tr The trace to use
   */
  protected SSCollector(SSTraceLocal tr) {
    ss = new CopyLocal();
    los = new LargeObjectLocal(Plan.loSpace);
    trace = tr;
  }

  /****************************************************************************
   *
   * Collection-time allocation
   */

  /**
   * Allocate space for copying an object (this method <i>does not</i>
   * copy the object, it only allocates space)
   *
   * @param original A reference to the original object
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  @Inline
  public Address allocCopy(ObjectReference original, int bytes,
      int align, int offset, int allocator) {
    if (allocator == Plan.ALLOC_LOS) {
      if (VM.VERIFY_ASSERTIONS) VM.assertions._assert(bytes > Plan.MAX_NON_LOS_COPY_BYTES);
      return los.alloc(bytes, align, offset);
    } else {
      if (VM.VERIFY_ASSERTIONS) {
        VM.assertions._assert(bytes <= Plan.MAX_NON_LOS_COPY_BYTES);
        VM.assertions._assert(allocator == SS.ALLOC_SS);
      }
      return ss.alloc(bytes, align, offset);
    }
  }

  /**
   * Perform any post-copy actions.
   *
   * @param object The newly allocated object
   * @param typeRef the type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   */
  @Inline
  public void postCopy(ObjectReference from, ObjectReference to, ObjectReference typeRef,
      int bytes, int allocator) {
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(ForwardingWord.isBusy(to));
      VM.assertions._assert(!ForwardingWord.isForwarded(to));
    }
    ForwardingWord.clearForwardingBits(to);
    ForwardingWord.setReplicatingBP(from, to); // set back pointer
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(!ForwardingWord.isBusy(to));
    }
    if (allocator == Plan.ALLOC_LOS)
      Plan.loSpace.initializeHeader(to, false);
    if (VM.VERIFY_ASSERTIONS) {
      VM.assertions._assert(getCurrentTrace().isLive(to));
      VM.assertions._assert(getCurrentTrace().willNotMoveInCurrentCollection(to));
    }
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Perform a per-collector collection phase.
   *
   * @param phaseId The collection phase to perform
   * @param primary Perform any single-threaded activities using this thread.
   */
  @Inline
  public void collectionPhase(short phaseId, boolean primary) {
    if (phaseId == SS.PREPARE) {
      // rebind the copy bump pointer to the appropriate semispace.
      if (SS.copyingAllComplete)
        ss.rebind(SS.toSpace());
      ss.linearScan(preGCSanity);
      los.prepare(true);
      trace.numObjectsCopied = 0;
      super.collectionPhase(phaseId, primary);
      return;
    }

    if (phaseId == SS.CLOSURE) {
      ss.linearScan(linearTrace);
      trace.completeTrace();
      int max = SSTraceLocal.numCopiesPerGCAllowed;
      int copied = trace.getNumObjectsCopied();
      if (copied < max) {
        Log.writeln("Everything copied ", copied);
        // SS.copyingAllComplete = true; // no more possible objects left to copy
      }
      return;
    }

    if (phaseId == SS.RELEASE) {
      trace.release();
      los.release(true);
      super.collectionPhase(phaseId, primary);
      return;
    }
    
    if (phaseId == SS.COMPLETE) {
      ss.linearScan(postGCSanity);
      if (SS.copyingAllComplete) {
        SS.tackOnLock.acquire();
        SS.deadThreadsBumpPointer.tackOn(ss);
        SS.tackOnLock.release();
      }
      super.collectionPhase(phaseId, primary);
      return;
    }

    super.collectionPhase(phaseId, primary);
  }


  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Return true if the given reference is to an object that is within
   * one of the semi-spaces.
   *
   * @param object The object in question
   * @return True if the given reference is to an object that is within
   * one of the semi-spaces.
   */
  public static boolean isSemiSpaceObject(ObjectReference object) {
    return Space.isInSpace(SS.SS0, object) || Space.isInSpace(SS.SS1, object);
  }

  /****************************************************************************
   *
   * Miscellaneous
   */

  /** @return The active global plan as an <code>SS</code> instance. */
  @Inline
  private static SS global() {
    return (SS) VM.activePlan.global();
  }

  /** @return the current trace object. */
  public TraceLocal getCurrentTrace() {
    return trace;
  }
}
