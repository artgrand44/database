package org.art.db.tests;

public interface ServerManager {
	void startInternal(String profile);
	void stopInternal();
	void startExternal(String profile);
	void stopExternal();
}
