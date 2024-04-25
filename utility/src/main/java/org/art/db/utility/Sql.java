package org.art.db.utility;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.art.db.client.Client;
import org.art.db.common.Configuration;
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sql {
	private static final Logger logger = LoggerFactory.getLogger(Sql.class);
			
	private static BufferedReader reader;
	private static PrintWriter writer;
	private static Console console;
	
	public static void main(String[] args) {
		String propertyPath = args.length == 0 ? "application.properties" : args[0];
		Client client = null;
		
		try {
			Configuration.getInstance().load(propertyPath);
			client = new Client(Configuration.getInstance());
			
			if (args.length == 3 && args[1].equals("-file")) {
				File file = new File(args[2]);
				
				if (!file.exists()) {
					writer.println("File " + file.getAbsolutePath() + " does not exist.");
					return;
				}
				
				reader = new BufferedReader(new FileReader(file));
				console = null;
			} else {
				reader = new BufferedReader(new InputStreamReader(System.in));
				console = System.console();
			}
			
			writer = new PrintWriter(new OutputStreamWriter(System.out));
		} catch (Exception exc) {
			System.out.print("There was an error getting the client " + exc.getMessage());
		}
		
		for (;;) {
			String command = "";
			try {
				command = getCommand().trim();

				System.out.println("command " + command);
				if (command.equalsIgnoreCase("quit")) {
					break;
				} else if (command.startsWith("use ")) {
					client.use(command.substring("use ".length()).trim());
				} else if (command.toLowerCase().startsWith("select ")) {
					printDocuments(client.executeQuery(command));
				} else {
					client.execute(command);
				}
			} catch (Exception exc) {
				writer.println("The commmand failed " + exc.getMessage());
				writer.println(command);
			}

		}
	}
	
	private static String getCommand() throws IOException {
		String command = "";
		
		
		if (console == null) {
			System.out.print("sql>");
			
			while (!command.endsWith(";")) {
				command += reader.readLine();
			}
		} else {
			while (!command.endsWith(";")) {
				command += System.console().readLine("sql>");
			}
		}
		
		return command.substring(0, command.length() - 1);
	}

	private static Map<String, Object> flatten(Document doc, String prefix) {
		Map<String, Object> result = new HashMap<>();
		
		for (Map.Entry<String, Object> entry : doc) {
			String name = entry.getKey();
			Object value = entry.getValue();
			String fullName =  prefix.isEmpty() ? name : prefix + "." + name;
			
			if (value instanceof Document subdoc) {
				result.putAll(flatten(subdoc,  fullName));
			} else {
				result.put(fullName, value);
			}
		}
		
		return result;
	}
	
	private static void printDocuments(DocumentSet set) {
		
		if (set.isEmpty()) {
			writer.println("No documents were retrieved");
			return;
		}
		
		List<Map<String, Object>> values = new ArrayList<>();
		Map<String, Integer> widths = new HashMap<>();
		Set<String> fields = new HashSet<>();
		
		for (Document doc : set) {
			Map<String, Object> flattened = flatten(doc, "");
			
			values.add(flattened);
			fields.addAll(flattened.keySet());
			
			for (Map.Entry<String, Object> entry : flattened.entrySet()) {
				String name = entry.getKey();
				int width = entry.getValue().toString().length();
				
				if (widths.containsKey(name)) {
					widths.put(name, Math.max(widths.get(name), width));					
				} else {
					widths.put(name, Math.max(name.length(), width));
				}				
			}
		}
		
		if (widths.isEmpty()) {
			writer.println("No columns were retrieved");
			return;
		}
		
		List<String> sorted = new ArrayList<>(fields);
		
		Collections.sort(sorted);
		
		int totalWidth = 1;
		
		for (Integer width : widths.values()) {
			totalWidth += (width + 3);
		}
		
		// Write the column names
		
		writer.println(StringUtils.rightPad("", totalWidth, '_'));
		sorted.forEach(x -> writeColumn(x, widths.get(x)));
		writer.println("|");
		writer.println(StringUtils.rightPad("", totalWidth, '_'));
		
		
		// Write the document values
		
		for (Map<String, Object> entry : values) {
			sorted.forEach(x -> writeColumn(entry.get(x), widths.get(x)));
			writer.println("|");
		}
		
		writer.println(StringUtils.rightPad("", totalWidth, '_'));
		writer.flush();
	}
	
	private static void writeColumn(Object value, int width) {
		writer.print("| " + StringUtils.rightPad(value.toString(), width + 1, ' '));
	}
	
}
