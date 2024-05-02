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
import org.art.db.common.Document;
import org.art.db.common.DocumentSet;

public class Sql {
	private static BufferedReader reader;
	private static Console console;
	
	private static Map<String, String> flatten(Document doc, String prefix) {
		Map<String, String> result = new HashMap<>();
		
		for (Map.Entry<String, Object> entry : doc) {
			String name = entry.getKey();
			Object value = entry.getValue();
			String fullName =  prefix.isEmpty() ? name : prefix + "." + name;
			
			if (value instanceof Document subdoc) {
				result.putAll(flatten(subdoc,  fullName));
			} else {
				result.put(fullName, value.toString().replaceAll("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
			}
		}
		
		return result;
	}
	
	public static void main(String[] args) {
		Client client = null;
		boolean verbose = false;
		String fileName = null;
		String commandLine = "sql [-file file] [-verbose] [-u user] [-p password]";
		String prompt = "";
		String verb = "";
		String user = null;
		String password = null;
		String cluster = null;
		
		try {
	
			// Parse the command line
			
			cluster = args.length == 0 ? "cluster" : args[0];
			
			for (int idx = 1; idx  < args.length; idx++) {
				if (args[idx].equals("-file") && idx < args.length - 1) {
					fileName = args[++idx];
				} else if (args[idx].equals("-u") && idx < args.length - 1) {
					user = args[++idx];
				} else if (args[idx].equals("-p") && idx < args.length - 1) {
					password = args[++idx];
				} else if (args[idx].equals("-verbose")) {
					verbose = true;
				} else {
					System.out.println(commandLine);
					return;
				}
			}
			
			// Open the input stream
			
			if (fileName != null) {
				File file = new File(fileName);
				
				if (!file.exists()) {
					System.out.println("File " + file.getAbsolutePath() + " does not exist.");
					return;
				}
				
				reader = new BufferedReader(new FileReader(file));
				console = null;
				prompt = "";
			} else {
				reader = new BufferedReader(new InputStreamReader(System.in));
				console = System.console();
				prompt = "sql>";
			}					
			
			while (user == null && fileName == null) {
				user = readLine("User name: ");
			}
			
			while (password == null && fileName == null) {
				password = readPassword("Password: ");
			}
			
			if (user == null || password == null) {
				System.out.println(commandLine);
				return;
			}
			
			try {
				client = new Client(cluster, user, password);
			} catch (Exception exc) {
				throw new Exception("There was an error connecting to the database.");
			}
		} catch (Exception exc) {
			exc.printStackTrace();
			System.out.println("There was an error getting the client " + exc.getMessage());
			return;
		}
					
		for (;;) {
			String command = "";
			
			try {
				command = readCommand(prompt).trim();
				
				if (command.isBlank()) {
					continue;
				}
				
				String[] tokens = command.toLowerCase().split(" ");
				boolean success = false;

				verb = tokens[0].substring(0, 1).toUpperCase() + tokens[0].substring(1);
				
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
						System.out.println("Exiting...");
						System.out.flush();
					}
					
					return;
				case "Select":
					printDocuments(client.executeQuery(command));
					success = true;
					break;
				default:
					System.out.println(verb + " is not a valid statement");
				}
				
				if (success && (verbose || fileName == null)) {
					System.out.println(verb + " successful");
				}
				
				System.out.flush();
			} catch (Exception exc) {
				System.out.println("The " + verb.toLowerCase() + " failed. " + exc.getMessage());
				System.out.flush();
			}

		}
	}
	
	private static void printDocuments(DocumentSet set) {
		
		if (set.isEmpty()) {
			System.out.println("No documents were retrieved");
			return;
		}
		
		System.out.println();
		
		List<Map<String, String>> values = new ArrayList<>();
		Map<String, Integer> widths = new HashMap<>();
		Set<String> fields = new HashSet<>();
		
		for (Document doc : set) {
			Map<String, String> flattened = flatten(doc, "");
			
			values.add(flattened);
			fields.addAll(flattened.keySet());
			
			for (Map.Entry<String, String> entry : flattened.entrySet()) {
				String name = entry.getKey();
				int width = entry.getValue().length();
				
				if (widths.containsKey(name)) {
					widths.put(name, Math.max(widths.get(name), width));					
				} else {
					widths.put(name, Math.max(name.length(), width));
				}				
			}
		}
		
		if (widths.isEmpty()) {
			System.out.println("No columns were retrieved");
			return;
		}
		
		List<String> sorted = new ArrayList<>(fields);
		
		Collections.sort(sorted);
		
		int totalWidth = 1;
		
		for (Integer width : widths.values()) {
			totalWidth += (width + 3);
		}
		
		// Write the column names
		
		System.out.println(StringUtils.rightPad("", totalWidth, '_'));
		sorted.forEach(x -> writeColumn(x, widths.get(x)));
		System.out.println("|");
		System.out.println(StringUtils.rightPad("", totalWidth, '_'));
		
		
		// Write the document values
		
		for (Map<String, String> entry : values) {
			sorted.forEach(x -> writeColumn(entry.get(x), widths.get(x)));
			System.out.println("|");
		}
		
		System.out.println(StringUtils.rightPad("", totalWidth, '_'));
		System.out.println();
		System.out.flush();
	}
	
	private static String readCommand(String prompt) throws IOException {
		String command = "";
		
		while (!command.endsWith(";")) {
			String line = reader.readLine();
			
			if (!line.trim().startsWith("//")) {
				command += line;
			}
		}
		
		return command.substring(0, command.length() - 1);
	}

	private static String readLine(String prompt)  throws IOException {
		if (console == null) {
			if (!prompt.isEmpty()) {
				System.out.print(prompt);
			}
			
			try {
			String line = reader.readLine();
			System.out.println("got line " + line);
			return line;
			} catch (Throwable thr) {
				thr.printStackTrace();
				return "";
			}
		}
		
		return console.readLine(prompt);
	}
	
	private static String readPassword(String prompt) throws IOException  {
		return console == null ? readLine(prompt) : new String(console.readPassword(prompt));
	}
	
	private static void writeColumn(String value, int width) {
		System.out.print("| " + StringUtils.rightPad(value.toString(), width + 1, ' '));
	}
	
}
