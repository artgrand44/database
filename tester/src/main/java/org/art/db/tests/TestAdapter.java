package org.art.db.tests;

import java.net.URI;

import org.art.db.client.Client;

public interface TestAdapter {
	URI startPrimary(String profile);
	void stopPrimary();
	URI startSecondary(String profile);
	void stopSecondary();
	Client getClusterClient(String user, String password) throws Exception;
	Client getPrimaryClient(String user, String password) throws Exception;
	Client getSecondaryClient(String user, String password) throws Exception;
}
