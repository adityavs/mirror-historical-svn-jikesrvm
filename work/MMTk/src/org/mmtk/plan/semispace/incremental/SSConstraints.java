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
import org.mmtk.policy.ReplicatingSpace;
import org.vmmagic.pragma.*;

@Uninterruptible
public class SSConstraints extends StopTheWorldConstraints {
  @Override
  public boolean movesObjects() { return true; }
  @Override
  public int gcHeaderBits() {
    return ReplicatingSpace.LOCAL_GC_BITS_REQUIRED;
  }
  @Override
  public int gcHeaderWords() {
    return ReplicatingSpace.GC_HEADER_WORDS_REQUIRED;
  }
  @Override
  public int numSpecializedScans() { return 1; }
  public boolean needsObjectReferenceReadBarrier() { return false; }
  
  // LPJH: later implement bulkCopy support

  /** @return True if this Plan replicates objects */
  public boolean replicatingGC() { return true;}

  /** @return True if this Plan requires write barriers on booleans. */
  public boolean needsBooleanWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk boolean arraycopy barriers. */
  public boolean booleanBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on bytes. */
  public boolean needsByteWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk byte arraycopy barriers. */
  public boolean byteBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on chars. */
  public boolean needsCharWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk char arraycopy barriers. */
  public boolean charBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on shorts. */
  public boolean needsShortWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk short arraycopy barriers. */
  public boolean shortBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on ints. */
  public boolean needsIntWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk int arraycopy barriers. */
  public boolean intBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on longs. */
  public boolean needsLongWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk long arraycopy barriers. */
  public boolean longBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on floats. */
  public boolean needsFloatWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk float arraycopy barriers. */
  public boolean floatBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on doubles. */
  public boolean needsDoubleWriteBarrier() { return true; }

  /** @return True if this Plan can perform bulk double arraycopy barriers. */
  public boolean doubleBulkCopySupported() { return false; }

  /** @return True if this Plan requires write barriers on Words. */
  public boolean needsWordWriteBarrier() { return true; }

  /** @return True if this Plan requires write barriers on Address's. */
  public boolean needsAddressWriteBarrier() { return true; }

  /** @return True if this Plan requires write barriers on Extents. */
  public boolean needsExtentWriteBarrier() { return true; }

  /** @return True if this Plan requires write barriers on Offsets. */
  public boolean needsOffsetWriteBarrier() { return true; }

  /** @return True if this Plan requires write barriers on object references. */
  public boolean needsObjectReferenceWriteBarrier() { return true; }
}
