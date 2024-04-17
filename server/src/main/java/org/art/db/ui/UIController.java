package org.art.db.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import  org.apache.commons.lang3.StringUtils;
import org.art.db.common.Configuration;
import org.art.db.common.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.StandardCacheManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.fasterxml.jackson.databind.ObjectMapper;

public class UIController {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(UIController.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private final UIPoller poller;
	
	private final TemplateEngine templateEngine;
	
	static final int PORT = 4446;
	
	public UIController(UIPoller poller) {
		this.poller = poller;
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		StandardCacheManager manager = new StandardCacheManager();
		
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        
        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
        this.templateEngine.setCacheManager(manager);
        
        Scheduler.DEFAULT.submit(() -> this.buildHtml("", false));

	}
	
	static record PropertyDescriptor(String property, String name, String pattern, String error) {
		
	}
	
	
	private PropertyDescriptor[] DESCRIPTORS = new PropertyDescriptor[] {
			new PropertyDescriptor("org.art.db.logDelay",       
		                           "Log propagation delay", 
		                           "^[0-9]+[mshdw]$",
		                           "The value must be a number followed by m, s, h, d, or w"),
			
			new PropertyDescriptor("org.art.db.documentCache",            
                                   "Document cache size",     
                                   "^[0-9]+[MGT]?$",
								   "The value must be a number followed by an optional M, G, or T"),
			
			new PropertyDescriptor("org.art.db.maxSegmentSize",            
                                   "In-memory segment size",     
                                   "^[0-9]+$",
                                   "The value must be a number"),
			
			new PropertyDescriptor("org.art.db.nodeCache",            
                                   "Node cache size",     
                                   "^[0-9]+[MGT]?$",
								   "The value must be a number followed by an optional M, G, or T"),
			
			
	};
	
	
	
	public String buildHtml(String currentPage, boolean isAdmin) throws Exception {
		
		Context context = new Context();        
               
        String services = this.getStatsAsString(ServerStats.class);
        String shards = this.getStatsAsString(ServerStats.class, x -> x.getShard());
        String clients = this.getStatsAsString(ClientStats.class, x -> x.getClient() + "|" + x.getSql());
        String queries = this.getStatsAsString(QueryStats.class);
        String alerts = this.getStatsAsString(AlertStats.class);
        String settings = this.getSettings();
        String nextPage = StringUtils.isAllBlank(currentPage) ? "Clusters" : currentPage;
        
               
        context.setVariable("admin",        isAdmin ? "true" : "false");
        context.setVariable("currentPage",  nextPage);
        context.setVariable("serversData",  services);
        context.setVariable("clustersData", shards);
        context.setVariable("alertData",    alerts);
        context.setVariable("queriesData",  queries);
        context.setVariable("settingsData", settings);
        context.setVariable("clientsData",  clients);

        return templateEngine.process("monitor", context);
        
	}
	
	private String getSettings() throws Exception  {
		Configuration config = Configuration.getInstance();
		List<Setting> result = new ArrayList<>();
		
		for (PropertyDescriptor prop :  DESCRIPTORS) {
			Object value = config.getProperty(prop.property, config.getDefault(prop.property));
			String str;
						
			if (prop.property.endsWith(".userPassword") || prop.property.endsWith(".adminPassword")) {
				str = "****";
			} else if (value == null) {
				str = "";
			} else {
				str = value.toString();				
			}
			
			result.add(new Setting(prop.property, prop.name, prop.pattern, prop.error, str));
		}
		
		return MAPPER.writeValueAsString(result);
	}
	
	private String getStatsAsString(Class<? extends Stats> clazz) throws Exception {
		return MAPPER.writeValueAsString(this.poller.getStats(clazz));
	}
	
	<T extends Stats> String getStatsAsString(Class<T> clazz, Function<T, String> getKey) throws Exception {
		return MAPPER.writeValueAsString(this.poller.getStats(clazz, getKey));
	}
	
	
}
