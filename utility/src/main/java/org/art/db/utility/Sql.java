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

public class Sql {
	private static BufferedReader reader;
	private static PrintWriter writer;
	private static Console console;
	
	public static void main(String[] args) {
		Client client = null;
		boolean verbose = false;
		String fileName = null;
		String commandLine = "sql [-file file] [-verbose]";
		String prompt = "";
		
		try {
			writer = new PrintWriter(new OutputStreamWriter(System.out));
			
			// Parse the command line
			
			String propertyPath = args.length == 0 ? "application.properties" : args[0];
			
			for (int idx = 1; idx  < args.length; idx++) {
				if (args[idx].equals("-file")) {
					if (idx == args.length) {
						writer.println(commandLine);
						return;
					}
					
					fileName = args[++idx];
				} else if (args[idx].equals("-verbose")) {
					verbose = true;
				} else {
					writer.println(commandLine);
					return;
				}
			}
			
			// Create the configuration and the client
			
			Configuration.getInstance().load(propertyPath);
			client = new Client(Configuration.getInstance());
					
			// Open the input stream
			
			if (fileName != null) {
				File file = new File(fileName);
				
				if (!file.exists()) {
					writer.println("File " + file.getAbsolutePath() + " does not exist.");
					return;
				}
				
				reader = new BufferedReader(new FileReader(file));
				console = null;
				prompt = "";
			} else {
				reader = new BufferedReader(new InputStreamReader(System.in));
				console = System.console();
			}
						
		} catch (Exception exc) {
			writer.println("There was an error getting the client " + exc.getMessage());
			return;
		}
		
		for (;;) {
			String command = "";
			
			try {
				command = getCommand(prompt).trim();
				
				if (command.isBlank()) {
					continue;
				}
				
				String[] tokens = command.toLowerCase().split(" ");
				String verb = tokens[0].substring(0, 1).toUpperCase() + tokens[0].substring(1);
				boolean success = false;

				switch (verb) {
				case "Alter":
				case "Create":
				case "Delete":
				case "Drop":
				case "Grant":
				case "Insert":
				case "Update":
					client.execute(command);
					success = true;
					break;
				case "Use":
					client.use(command.substring("use ".length()).trim());
					success = true;
					break;
				case "Quit":
					if (verbose || fileName == null) {
						writer.println("Exiting...");
						writer.flush();
					}
					
					return;
				case "Select":
					printDocuments(client.executeQuery(command));
					success = true;
					break;
				default:
					writer.println(verb + " is not a valid statement");
				}
				
				if (success && (verbose || fileName == null)) {
					writer.println(verb + " successful");
				}
				
				writer.flush();
			} catch (Exception exc) {
				writer.println("The commmand failed " + exc.getMessage());
				writer.println(command);
				writer.flush();
			}

		}
	}
	
	private static String getCommand(String prompt) throws IOException {
		String command = "";
		
		
		if (console == null) {
			System.out.print(prompt);
			
			while (!command.endsWith(";")) {
				command += reader.readLine();
			}
		} else {
			while (!command.endsWith(";")) {
				command += System.console().readLine(prompt);
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
