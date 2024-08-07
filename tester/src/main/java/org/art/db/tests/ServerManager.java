package org.art.db.tests;

import java.net.URI;

public interface ServerManager {
	URI startPrimary(String profile);
	void stopPrimary();
	URI startSecondary(String profile);
	void stopSecondary();
}
