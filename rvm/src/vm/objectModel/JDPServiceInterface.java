/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$

/**
 * Interface of services provided by JDP to the object model.
 *
 * @author Stephen Fink
 */
public interface JDPServiceInterface {
  /**
   * Return the contents of a memory location from the debuggee process.
   *
   * @param ptr the memory location
   */
  int readMemory(ADDRESS ptr);
}
