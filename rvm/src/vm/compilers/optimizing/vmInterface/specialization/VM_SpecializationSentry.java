/*
 * (C) Copyright IBM Corp. 2001
 */

class VM_SpecializationSentry {
    static private boolean specializationResultsValid = false;

    static public boolean isValid() {
	return specializationResultsValid;
    }

    static public boolean setValid(boolean newValidity) {
	return specializationResultsValid = newValidity;
    }
}


