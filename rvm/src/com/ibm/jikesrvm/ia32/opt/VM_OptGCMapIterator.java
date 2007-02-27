/*
 * This file is part of Jikes RVM (http://jikesrvm.sourceforge.net).
 * The Jikes RVM project is distributed under the Common Public License (CPL).
 * A copy of the license is included in the distribution, and is also
 * available at http://www.opensource.org/licenses/cpl1.0.php
 *
 * (C) Copyright IBM Corp. 2001, 2004
 */
package com.ibm.jikesrvm.ia32.opt;
import com.ibm.jikesrvm.VM;
import com.ibm.jikesrvm.VM_SizeConstants;
import com.ibm.jikesrvm.opt.VM_OptGenericGCMapIterator;

import org.vmmagic.pragma.*;
import org.vmmagic.unboxed.*;

/**
 * An instance of this class provides iteration across the references 
 * represented by a frame built by the OPT compiler.
 *
 * The architecture-specific version of the GC Map iterator.  It inherits
 * its architecture-independent code from VM_OptGenericGCMapIterator.
 * This version is for IA32
 *
 * @author Michael Hind
 */
@Uninterruptible public abstract class VM_OptGCMapIterator extends VM_OptGenericGCMapIterator
  implements VM_SizeConstants {

  private static final boolean DEBUG = false;
 
  public VM_OptGCMapIterator(WordArray registerLocations) {
    super(registerLocations);
  }

  /** 
   * If any non-volatile gprs were saved by the method being processed
   * then update the registerLocations array with the locations where the
   * registers were saved.  Also, check for special methods that also
   * save the volatile gprs.
   */
  protected void updateLocateRegisters() {

    //           HIGH MEMORY
    //
    //       +---------------+                                           |
    //  FP-> |   saved FP    |  <-- this frame's caller's frame          |
    //       +---------------+                                           |
    //       |    cmid       |  <-- this frame's compiledmethod id       |
    //       +---------------+                                           |
    //       |               |                                           |
    //       |  Spill Area   |  <-- spills and other method-specific     |
    //       |     ...       |      compiler-managed storage             |
    //       +---------------+                                           |
    //       |   Saved FP    |     only SaveVolatile Frames              |   
    //       |    State      |                                           |
    //       +---------------+                                           |
    //       |  VolGPR[0]    |                                           
    //       |     ...       |     only SaveVolatile Frames              
    //       |  VolGPR[n]    |                                           
    //       +---------------+                                           
    //       |  NVolGPR[k]   |  <-- cm.getUnsignedNonVolatileOffset()  
    //       |     ...       |   k == cm.getFirstNonVolatileGPR()      
    //       |  NVolGPR[n]   |                                           
    //       +---------------+                                           
    //
    //           LOW MEMORY
    
    int frameOffset = compiledMethod.getUnsignedNonVolatileOffset();
    if (frameOffset >= 0) {
      // get to the non vol area
      Address nonVolArea = framePtr.minus(frameOffset);
    
      // update non-volatiles
      int first = compiledMethod.getFirstNonVolatileGPR();
      if (first >= 0) {
        // move to the beginning of the nonVol area
        Address location = nonVolArea;
        
        for (int i = first; i < NUM_NONVOLATILE_GPRS; i++) {
          // determine what register index corresponds to this location
          int registerIndex = NONVOLATILE_GPRS[i];
          registerLocations.set(registerIndex, location.toWord());
          if (DEBUG) {
            VM.sysWrite("UpdateRegisterLocations: Register ");
            VM.sysWrite(registerIndex);
            VM.sysWrite(" to Location ");
            VM.sysWrite(location);
            VM.sysWrite("\n");
          }
          location = location.minus(BYTES_IN_ADDRESS);
        }
      }
      
      // update volatiles if needed
      if (compiledMethod.isSaveVolatile()) {
        // move to the beginning of the nonVol area
        Address location = nonVolArea.plus(4 * NUM_VOLATILE_GPRS);
        
        for (int i = 0; i < NUM_VOLATILE_GPRS; i++) {
          // determine what register index corresponds to this location
          int registerIndex = VOLATILE_GPRS[i];
          registerLocations.set(registerIndex, location.toWord());
          if (DEBUG) {
            VM.sysWrite("UpdateRegisterLocations: Register ");
            VM.sysWrite(registerIndex);
            VM.sysWrite(" to Location ");
            VM.sysWrite(location);
            VM.sysWrite("\n");
          }
          location = location.minus(BYTES_IN_ADDRESS);
        }
      }
    }
  }

  /** 
   *  Determine the spill location given the frame ptr and spill offset.
   *  (The location of spills varies among architectures.)
   *  @param framePtr the frame pointer
   *  @param offset  the offset for the spill 
   *  @return the resulting spill location
   */
  public Address getStackLocation(Address framePtr, int offset) {
    return framePtr.minus(offset);
  }

  /** 
   *  Get address of the first spill location for the given frame ptr
   *  @return the first spill location
   */
  public Address getFirstSpillLoc() {
    return framePtr.minus(-VM.STACKFRAME_BODY_OFFSET);
  }

  /** 
   *  Get address of the last spill location for the given frame ptr
   *  @return the last spill location
   */
  public Address getLastSpillLoc() {
    if (compiledMethod.isSaveVolatile()) {
      return framePtr.minus(compiledMethod.getUnsignedNonVolatileOffset() - 4 - SAVE_VOL_SIZE);
    } else {
      return framePtr.minus(compiledMethod.getUnsignedNonVolatileOffset() - 4);
    }
  }

  static final int VOL_SIZE = 4 * NUM_VOLATILE_GPRS;
  static final int SAVE_VOL_SIZE = VOL_SIZE + VM.FPU_STATE_SIZE;
}
