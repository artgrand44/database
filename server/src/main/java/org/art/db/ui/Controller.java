package org.art.db.ui;

import java.util.ArrayList;
import java.util.List;

import  org.apache.commons.lang3.StringUtils;
import org.art.db.common.KubernetesPlugin;
import org.art.db.common.Scheduler;
import org.art.db.storage.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.StandardCacheManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.fasterxml.jackson.databind.ObjectMapper;

public class Controller {
	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Controller.class);
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	static final int PORT = 4446;
	
	private final Model model;
	
	private long lastUpdate = 0;
	
	private final TemplateEngine templateEngine;
	
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
	
	public Controller(Model model) {
		this.model = model;
		
		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		StandardCacheManager manager = new StandardCacheManager();
		
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        
        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(resolver);
        this.templateEngine.setCacheManager(manager);
        
        Scheduler.DEFAULT.submit(() -> this.buildHtml(""));

	}
	
	
	public synchronized String buildHtml(String currentPage) throws Exception {
		
		Context context = new Context();        
               
		if (System.currentTimeMillis() - this.lastUpdate > 10 * 1000 || ! StringUtils.isAllBlank(currentPage)) {
			this.model.refresh();
			this.lastUpdate = System.currentTimeMillis();
		}
			
		
        String services = MAPPER.writeValueAsString(this.model.getServerStats());
        String shards = MAPPER.writeValueAsString(this.model.getServerStats(x -> x.getShard()));
        String clients = MAPPER.writeValueAsString(this.model.getClientStats(x -> x.getClient() + "|" + x.getSql()));
        String queries = MAPPER.writeValueAsString(this.model.getQueryStats());
        String alerts = MAPPER.writeValueAsString(this.model.getAlertStats());
        String settings = this.getSettings();
        String nextPage = StringUtils.isAllBlank(currentPage) ? "Clusters" : currentPage;
        
               
        context.setVariable("admin",        KubernetesPlugin.isKubernetes() ? "false" : "true");
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
		Environment config = Environment.getInstance();
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
	
	static record PropertyDescriptor(String property, String name, String pattern, String error) {
		
	}
	
	
}
