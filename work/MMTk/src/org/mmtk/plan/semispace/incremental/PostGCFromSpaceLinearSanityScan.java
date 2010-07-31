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
public class PostGCFromSpaceLinearSanityScan extends LinearScan {
  /**
   * Scan an object. ToSpace must not contain pointers to fromSpace after a complete trace
   * @param object The object to scan
   */
  public void scan(ObjectReference object) {
    if (VM.VERIFY_ASSERTIONS) {
      if (!object.isNull()) {
        // Log.write("Scanning... "); Log.writeln(object);
        VM.assertions._assert(!SS.copyingAllComplete); // If copying is complete space should be empty
        VM.assertions._assert(Space.isInSpace(SS.fromSpace().getDescriptor(), object)); // flip can't have happened as not all
                                                                                        // copying is complete

        // if (VM.scanning.pointsToForwardedObjects(object)) {
        // Log.write("PostGCFromSpaceLinearSanityScan: Object ");
        // Log.write(object);
        // Log.writeln(" contained references to a forwarded fromSpace object");
        // VM.assertions.fail("Died during linear sanity scan");
        // }

        VM.assertions._assert(!ForwardingWord.isBusy(object));

        if (ForwardingWord.isForwardedOrBeingForwarded(object)) {
          VM.assertions._assert(ForwardingWord.isForwarded(object)); // can't be half way through copying the object
          ObjectReference forwarded = ForwardingWord.getReplicatingFP(object);
          VM.assertions._assert(Space.isInSpace(SS.toSpace().getDescriptor(), forwarded));
          VM.assertions._assert(!forwarded.isNull());
          VM.assertions._assert(VM.assertions.validRef(forwarded));
          // follow FP and then follow BP - hope we end up at the same object!
          ObjectReference bpObj = ForwardingWord.getReplicatingFP(forwarded);
          VM.assertions._assert(object == bpObj);
          VM.objectModel.checkFromSpaceReplicatedObject(object, forwarded);
        } else {
          VM.objectModel.checkFromSpaceNotYetReplicatedObject(object);
        }
      }
    }
  }
}
