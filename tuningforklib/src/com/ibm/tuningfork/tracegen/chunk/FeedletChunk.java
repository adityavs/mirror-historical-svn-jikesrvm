/*
 * This file is part of the Tuning Fork Visualization Platform
 *  (http://sourceforge.net/projects/tuningforkvp)
 *
 * Copyright (c) 2005 - 2008 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 */

package com.ibm.tuningfork.tracegen.chunk;

public class FeedletChunk extends Chunk {

    public static final int FEEDLET_TYPE_ID = 2;
    public static final int FEEDLET_COUNT_OFFSET = Chunk.DATA_OFFSET;
    public static final int FEEDLET_DATA_OFFSET = FEEDLET_COUNT_OFFSET + 4;
    public static final int FEEDLET_ADD_OPERATION = 1;
    public static final int FEEDLET_REMOVE_OPERATION = 2;
    public static final int FEEDLET_DESCRIBE_OPERATION = 3;
    public static final String NAME_PROPERTY = "name";
    public static final String DECSRIPTION_PROPERTY = "description";

    private int feedletOperations = 0;

    public FeedletChunk() {
	super(FEEDLET_TYPE_ID);
	seek(FEEDLET_DATA_OFFSET);
    }

    public boolean hasData() {
	return feedletOperations > 0;
    }

    public synchronized void add(int feedletIndex, String name, String description) {
	if (!hasRoom(ENCODING_SPAGE_INT * 6
		+ encodingSpace(NAME_PROPERTY)
		+ encodingSpace(DECSRIPTION_PROPERTY)
		+ encodingSpace(name) + encodingSpace(description))) {
	    System.err.println("FeedletChunk.add ran out of room");
	    return;
	}
	addInt(FEEDLET_ADD_OPERATION);
	addInt(feedletIndex);
	feedletOperations++;
	addProperty(feedletIndex, NAME_PROPERTY, name);
	addProperty(feedletIndex, DECSRIPTION_PROPERTY, description);
    }

    public synchronized boolean addProperty(int feedletIndex, String key, String val) {
	if (!hasRoom(ENCODING_SPAGE_INT * 2 + encodingSpace(key) + encodingSpace(val))) {
	    return false;
	}
	addInt(FEEDLET_DESCRIBE_OPERATION);
	addInt(feedletIndex);
	addString(key);
	addString(val);
	feedletOperations++;
	return true;
    }

    public synchronized void close() {
	int pos = getPosition();
	seek(FEEDLET_COUNT_OFFSET);
	addInt(feedletOperations);
	seek(pos);
	feedletOperations = 0;
	super.close();
    }
}
