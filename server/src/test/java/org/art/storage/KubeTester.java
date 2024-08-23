package org.art.storage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;

public class KubeTester {
	private static final Logger logger = LoggerFactory.getLogger(KubeTester.class);
	
	private static int totalTests = 0;
	private static int totalErrors = 0;
	private static List<String> messages = new ArrayList<>();
			
	public static void main(String[] args) {
		
		try {
			System.setProperty("org.art.db.serviceURI", "http://localhost:8080");
			
			FastClasspathScanner scanner = new FastClasspathScanner(new String[] {"org.art.storage"} );
			
			scanner.scan();

			List<String> classNames = scanner.getClassesWithAnnotation(Integration.class);

			Collections.sort(classNames);

			
			for (String className : classNames) {
				runTests(className);
			}

			StringBuilder builder = new StringBuilder();
			
			builder.append("\nTotal tests: " + totalTests + " Total Errors: " + totalErrors);
			
			if (!messages.isEmpty()) {
				builder.append("\nMesssages:\n");
				
				for (String message : messages) {
					builder.append("    " + message);
				}
			}
			
			logger.info(builder.toString());
		} catch (Exception exc) {
			logger.info("Exception in main " + exc.getMessage());
			exc.printStackTrace();
		}
	}
	
	private static void runTests(String className) {
		try {
			Method beforeEach = null;
			Method afterEach = null;
			List<Method> tests = new ArrayList<>();
			Class<?> clazz = Class.forName(className);
			int mods = clazz.getModifiers();
			
			if (!Modifier.isPublic(mods) || Modifier.isAbstract(mods)) {
				return;
			}
			
			for (Method method : clazz.getMethods()) {
				if (method.getAnnotation(BeforeEach.class) != null) {
					beforeEach = method;
				} else if (method.getAnnotation(AfterEach.class) != null) {
					afterEach = method;
				} else if (method.getAnnotation(Test.class) != null) {
					tests.add(method);
				}
			}
			
			Constructor<?> cons = clazz.getConstructor();
			Object instance = cons.newInstance();

			logger.info("Starting class " + clazz.getSimpleName());
			
			BaseIntegrationTest.setKunernetes(true);
			
			for (Method test : tests) {
				totalTests++;
				
				try {
					if (beforeEach != null) {
						beforeEach.invoke(instance);
					}
					
					test.invoke(instance);
					
					if (afterEach != null) {
						afterEach.invoke(instance);
					}
				} catch (Throwable thr) {
					Throwable theThrowable = thr;
					Throwable cause;
					String message;
					
					while ((cause = theThrowable.getCause()) != null) {
						theThrowable = cause;
					}
					
					message= theThrowable.getMessage() == null ? theThrowable.getClass().getName() : theThrowable.getMessage();
					
					logger.error("\n        Test failed " + message);
					
					messages.add(StringUtils.rightPad(clazz.getSimpleName() + ":" + test.getName(), 30) + " - " + message);
					totalErrors++;
				}
			}			
		} catch (Throwable thr1) {
			thr1.printStackTrace();
		}
		
	}

}
