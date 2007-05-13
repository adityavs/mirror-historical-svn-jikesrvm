/*
 * This file is part of MMTk (http://jikesrvm.sourceforge.net).
 * MMTk is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2004
 */
package org.mmtk.policy;

import org.mmtk.utility.alloc.BumpPointer;

import org.vmmagic.pragma.*;

/**
 * This class implements unsynchronized (local) elements of an
 * immortal space. Allocation is via the bump pointer 
 * (@see BumpPointer). 
 * 
 * @see BumpPointer
 * @see ImmortalSpace
 */
@Uninterruptible public final class ImmortalLocal extends BumpPointer {

  /**
   * Constructor
   * 
   * @param space The space to bump point into.
   */
  public ImmortalLocal(ImmortalSpace space) {
    super(space, true);
  }
}
