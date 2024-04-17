package org.art.db.ui;

import org.art.db.common.Configuration;;

public class UIConsole {
	
	private final UIPublisher publisher;
	private final UIPoller poller;
	private final UIController controller;
	
	private final static UIConsole instance = new UIConsole();
	
	public UIConsole() {
		Configuration config = Configuration.getInstance();
		this.publisher = new UIPublisher(config);
		this.poller = new UIPoller(config);
		this.controller = new UIController(poller);		
	}

	public String buildHtml(String currentPage, boolean isAdmin) throws Exception {
		return this.controller.buildHtml(currentPage, isAdmin);
	}
	
	public static UIConsole getInstance() {
		return instance;
	}
	

	public void logDelete() {
		this.publisher.logDelete();
	}

	
	public void logClient(String clientName, String sql, long time, long calls, long matches) {
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

	public void start() {
		this.publisher.start();
		this.poller.start();
	}
	
	public void stop() {
		this.publisher.stop();
		this.poller.stop();
	}
}
