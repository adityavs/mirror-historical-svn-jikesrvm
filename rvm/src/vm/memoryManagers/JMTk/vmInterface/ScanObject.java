/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

package com.ibm.JikesRVM.memoryManagers.vmInterface;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_Constants;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_ObjectModel;
import com.ibm.JikesRVM.VM_Atom;
import com.ibm.JikesRVM.VM_Type;
import com.ibm.JikesRVM.VM_Class;
import com.ibm.JikesRVM.VM_Array;
import com.ibm.JikesRVM.VM_Method;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_PragmaLogicallyUninterruptible;
import com.ibm.JikesRVM.VM_Scheduler;
import com.ibm.JikesRVM.VM_Memory;
import com.ibm.JikesRVM.VM_Time;
import com.ibm.JikesRVM.VM_Entrypoints;
import com.ibm.JikesRVM.VM_Reflection;
import com.ibm.JikesRVM.VM_Synchronization;

/**
 * Class that supports scanning Objects or Arrays for references
 * during Collection and processing those references
 *
 * @author Stephen Smith
 */  
public class ScanObject implements VM_Constants, Constants {

  /**
   * Scans an object or array for internal object references and
   * processes those references (calls processPtrField)
   *
   * @param objRef  reference for object to be scanned (as int)
   */
  static void scanObjectOrArray(VM_Address objRef ) throws VM_PragmaUninterruptible {

    // First process the TIB to relocate it.
    // Necessary only if the allocator/collector moves objects
    // and the object model is actually storing the TIB as a pointer.
    // 
    if (VM_Interface.MOVES_OBJECTS) {
      VM_ObjectModel.gcProcessTIB(objRef);
    }

    Object obj = VM_Magic.addressAsObject(objRef);
    Object[] tib = VM_ObjectModel.getTIB(obj);
    if (VM.VerifyAssertions) {
      if (tib == null || VM_ObjectModel.getObjectType(tib) != VM_Type.JavaLangObjectArrayType) {
        VM.sysWrite("ScanObject: tib is not Object[]\n");
        VM.sysWrite("               objRef = ");
        VM.sysWrite(objRef);
        VM.sysWrite("               tib = ");
        VM.sysWrite(VM_Magic.objectAsAddress(tib));
        VM.sysWrite("\n");
      }
    }
    VM_Type type = VM_Magic.objectAsType(tib[TIB_TYPE_INDEX]);
    if (VM.VerifyAssertions) {
      if (type == null) {
        VM.sysWrite("ScanObject: type is null\n");
        VM.sysWrite("               objRef = ");
        VM.sysWrite(objRef);
        VM.sysWrite("\nScanObject: objRef = ");
        VM.sysWrite(VM_Magic.objectAsAddress(type));
        VM.sysWrite("\n");
        VM._assert(type != null);
      }
    }
    if ( type.isClassType() ) {
      int[] referenceOffsets = type.asClass().getReferenceOffsets();
      for(int i = 0, n=referenceOffsets.length; i < n; i++) {
        VM_Interface.processPtrField( objRef.add(referenceOffsets[i]) );
      }
      Statistics.profileScan(obj, 4 * referenceOffsets.length, tib);
    }
    else {
      if (VM.VerifyAssertions) VM._assert(type.isArrayType());
      VM_Type elementType = type.asArray().getElementType();
      if (elementType.isReferenceType()) {
        int num_elements = VM_Magic.getArrayLength(obj);
        int numBytes = num_elements * WORD_SIZE;
        VM_Address location = objRef;    // for arrays = address of [0] entry
        VM_Address end      = objRef.add(numBytes);
        while ( location.LT(end) ) {
          VM_Interface.processPtrField( location );
          location = location.add(WORD_SIZE);  // is this size_of_pointer ?
        }
        Statistics.profileScan(obj, numBytes, tib);
      }
    }
  } 

  static void scanObjectOrArray( Object objRef ) throws VM_PragmaUninterruptible {
    scanObjectOrArray( VM_Magic.objectAsAddress(objRef) );
  }

  public static boolean validateRefs( VM_Address ref, int depth ) throws VM_PragmaUninterruptible {

    VM_Type    type;

    if (ref.isZero()) return true;   // null is always valid

    // First check passed ref, before looking into it for refs
    if ( !Util.validRef(ref) ) {
      VM.sysWrite("ScanObject.validateRefs: Bad Ref = ");
      Util.dumpRef( ref );
      VM_Memory.dumpMemory(ref, 32, 32 );  // dump 16 words on either side of bad ref
      return false;
    }

    if (depth==0) return true;  //this ref valid, stop depth first scan

    type = VM_Magic.getObjectType(VM_Magic.addressAsObject(ref));
    if ( type.isClassType() ) {
      int[] referenceOffsets = type.asClass().getReferenceOffsets();
      for (int i = 0, n=referenceOffsets.length; i < n; i++) {
        VM_Address iref = VM_Magic.getMemoryAddress(ref.add(referenceOffsets[i]));
        if ( ! validateRefs( iref, depth-1 ) ) {
          VM.sysWrite("Referenced from Object: Ref = ");
          Util.dumpRef( ref );
          VM.sysWrite("                  At Offset = ");
          VM.sysWrite(referenceOffsets[i],false);
          VM.sysWrite("\n");
          return false;
        }
      }
    }
    else {
      VM_Type elementType = type.asArray().getElementType();
      if (elementType.isReferenceType()) {
        int num_elements = VM_Magic.getArrayLength(VM_Magic.addressAsObject(ref));
        int location = 0;    // for arrays = offset of [0] entry
        int end      = num_elements * 4;
        for ( location = 0; location < end; location += 4 ) {
          VM_Address iref = VM_Address.fromInt(VM_Magic.getMemoryWord(ref.add(location)));
          if ( ! validateRefs( iref, depth-1 ) ) {
            VM.sysWrite("Referenced from Array: Ref = ");
            Util.dumpRef( ref );
            VM.sysWrite("                  At Index = ");
            VM.sysWrite((location>>2),false);
            VM.sysWrite("              Array Length = ");
            VM.sysWrite(num_elements,false);
            VM.sysWrite("\n");
            return false;
          }
        }
      }
    } 
    return true;
  } // validateRefs

}   // ScanObject
