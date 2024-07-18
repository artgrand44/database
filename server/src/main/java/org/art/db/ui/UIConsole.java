package org.art.db.ui;

import org.art.db.common.ConnectionInfo;
import org.art.db.storage.Environment;;

public class UIConsole {
	
	private UIPublisher publisher;
	private UIPoller poller;
	private UIController controller;
	
	private final static UIConsole instance = new UIConsole();
	
	private UIConsole() {	
	}

	public String buildHtml(String currentPage, boolean isAdmin) throws Exception {
		return this.controller == null ? "" : this.controller.buildHtml(currentPage, isAdmin);
	}
	
	public static UIConsole getInstance() {
		return instance;
	}
	
	public void logAlert(String alert) {
		this.publisher.logAlert(alert);
	}
	
	public void logDelete() {
		this.publisher.logDelete();
	}
	
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		this.publisher.logClient(clientName, sql, time, calls, matches);
	}	

	public void logInsert() {
		this.publisher.logInsert();
	}

	public void logUpdate() {
		this.publisher.logUpdate();
	}

	public void logSelect(String queryString, long time, int matches) {
		this.publisher.logSelect(queryString, time, matches);
	}

	public void start(ConnectionInfo info) {
		
		if (Environment.getInstance().getProperty("org.art.db.logConsole", "false").equalsIgnoreCase("true")) {
			this.publisher = new UIPublisher(info);
			this.poller = new UIPoller(info);
			this.controller = new UIController(poller);	
			
			this.publisher.start();
			this.poller.start();
		}
	}
	
	public void stop() {
		if (Environment.getInstance().getProperty("org.art.db.logConsole", "false").equalsIgnoreCase("true")) {
			this.publisher.stop();
			this.poller.stop();
		}
	}
}
