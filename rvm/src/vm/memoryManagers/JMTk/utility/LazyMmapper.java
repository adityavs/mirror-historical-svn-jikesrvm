/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 * (C) Copyright IBM Corp. 2002
 */

package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.Conversions;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Uninterruptible;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;

/**
 * This class implements lazy mmapping of virtual memory.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public final class LazyMmapper implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Public static methods 
  //
  //

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).
   */
  {
    mapped = new boolean[NUM_CHUNKS];
    for (int c = 0; c < NUM_CHUNKS; c++) {
      mapped[c] = false;
    }
  }

  public static void ensureMapped(VM_Address start, int blocks) {
    int chunk = Conversions.addressToMmapChunks(start);
    int sentinal = chunk + Conversions.blocksToMmapChunks(blocks);
    while (chunk < sentinal) {
      if (!mapped[chunk]) {
	if (!VM_Interface.mmap(Conversions.mmapChunksToAddress(chunk), VM_Interface.MMAP_CHUNK_BYTES)) {
	  VM.sysWriteln("ensureMapped failed");
	  VM._assert(false);
	}
	mapped[chunk] = true;
      }
      chunk++;
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Private static methods and variables
  //
  private static boolean mapped[];
  private static int LOG_MMAP_CHUNK_SIZE = 20;            
  private static int MMAP_CHUNK_SIZE = 1 << LOG_CHUNK_SIZE;   // the granularity VMResource operates at
  private static int MMAP_NUM_CHUNKS = 1 << (Constants.LOG_ADDRESS_SPACE - LOG_CHUNK_SIZE);
}

