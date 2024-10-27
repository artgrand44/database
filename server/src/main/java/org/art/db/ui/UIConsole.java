package org.art.db.ui;

import org.art.db.common.ConnectionInfo;
import org.art.db.storage.MetricsLogger;

import com.fasterxml.jackson.databind.ObjectMapper;;

public class UIConsole extends MetricsLogger {
	
	private LocalModel localModel;
	private Model model;
	private Controller controller;
	
	private final static UIConsole instance = new UIConsole();
	
	private final static ObjectMapper MAPPER = new ObjectMapper();
	
	private UIConsole() {	
	}

	public String buildHtml(String currentPage) throws Exception {
		return this.controller == null ? "To enable the console set org.art.db.metricslogger=console" : this.controller.buildHtml(currentPage);
	}
	
	public static UIConsole getInstance() {
		return instance;
	}
	
	public byte[] getStatsAsJson() throws Exception {
		return MAPPER.writeValueAsBytes(this.localModel.getStats());
	}
	
	public void logAlert(String alert) {
		this.localModel.logAlert(alert);
	}
	
	public void logDelete() {
		this.localModel.logDelete();
	}
	
	public void logGet() {
		this.localModel.logGet();
	}
	
	public void logClient(String clientName, String sql, long time, long calls, int matches) {
		this.localModel.logClient(clientName, sql, time, calls, matches);
	}	

	public void logInsert() {
		this.localModel.logInsert();
	}

	public void logUpdate() {
		this.localModel.logUpdate();
	}

	public void logSelect(String queryString, long time, int matches) {
		this.localModel.logSelect(queryString, time, matches);
	}

	public void logStatus(String status) {
		this.localModel.logStatus(status);
	}
	public void start(ConnectionInfo info) {
		
		this.localModel = new LocalModel(info);
		this.model = new Model(info, this.localModel);
		this.controller = new Controller(this.model);	

	}
	
	public void stop() {
	
	}
}
