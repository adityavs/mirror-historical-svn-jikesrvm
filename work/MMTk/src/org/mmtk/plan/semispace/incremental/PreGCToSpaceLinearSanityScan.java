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

import org.mmtk.policy.Space;
import org.mmtk.utility.ForwardingWord;
import org.mmtk.utility.Log;
import org.mmtk.utility.alloc.LinearScan;
import org.mmtk.vm.VM;
import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * Callbacks from BumpPointer during a linear scan are dispatched through
 * a subclass of this object.
 */
@Uninterruptible
public class PreGCToSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object. ToSpace must not contain pointers to fromSpace after a complete trace
   * @param object The object to scan
   */
  public void scan(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        VM.assertions._assert(!SS.copyingAllComplete); // If copying is complete space should be empty
        // Log.write("Scanning... "); Log.writeln(object);
        // if (VM.scanning.pointsToForwardedObjects(object)) {
        // Log.write("PreGCToSpaceLinearSanityScan: Object ");
        // Log.write(object);
        // Log.writeln(" contained references to a forwarded fromSpace object");
        // VM.assertions.fail("Died during linear sanity scan");
        // }

        VM.assertions._assert(!ForwardingWord.isBusy(object));
        ObjectReference bp = ForwardingWord.getReplicatingFP(object);
        if (ForwardingWord.isForwarded(object)) {
          VM.assertions._assert(!bp.isNull());
          VM.assertions._assert(Space.isInSpace(SS.fromSpace().getDescriptor(), bp));
          VM.assertions._assert(VM.assertions.validRef(bp));
          // follow BP and then follow FP - hope we end up at the same object!
          ObjectReference bpObj = ForwardingWord.getReplicatingFP(bp);
          VM.assertions._assert(object == bpObj);
        } else {
          // an object in toSpace with no fromSpace replica
          VM.assertions._assert(bp.isNull());
          VM.objectModel.checkFromSpaceNotYetReplicatedObject(object);
        }
      }
    }
  }
}
