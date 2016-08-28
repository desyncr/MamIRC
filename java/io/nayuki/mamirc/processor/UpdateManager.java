/* 
 * MamIRC
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/mamirc-the-headless-irc-client
 * https://github.com/nayuki/MamIRC
 */

package io.nayuki.mamirc.processor;

import java.util.ArrayList;
import java.util.List;


/* 
 * Buffers sequential updates and allows waiting for and retrieving them. Thread-safe.
 */
final class UpdateManager {
	
	/*---- Fields ----*/
	
	private int nextUpdateId;
	
	private List<Object[]> recentUpdates;
	
	
	
	/*---- Constructors ----*/
	
	public UpdateManager() {
		nextUpdateId = 0;
		recentUpdates = new ArrayList<>();
	}
	
	
	
	/*---- Methods ----*/
	
	public synchronized void addUpdate(Object... data) {
		recentUpdates.add(data);
		nextUpdateId++;
		if (recentUpdates.size() > 10000)  // Purge old updates to reduce memory usage
			recentUpdates.subList(0, recentUpdates.size() / 2).clear();
		this.notifyAll();
	}
	
	
	public synchronized List<Object[]> getUpdates(int startId, int maxWait) throws InterruptedException {
		if (maxWait < 0 || startId < 0)
			throw new IllegalArgumentException();
		while (maxWait > 0 && startId == nextUpdateId)
			this.wait();
		int size = recentUpdates.size();
		if (nextUpdateId - size <= startId && startId <= nextUpdateId)
			return new ArrayList<>(recentUpdates.subList(size - (nextUpdateId - startId), size));
		else  // startId is too early or too late; need caller to resynchronize fully
			return null;
	}
	
}
