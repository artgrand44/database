package org.art.db.tests;

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

public class TestRunner {
	private static final Logger logger = LoggerFactory.getLogger(TestRunner.class);
	
	private static int totalTests = 0;
	private static int totalErrors = 0;
	private static List<String> messages = new ArrayList<>();
	
	private static TestAdapter adapter;
			
	
	public static void main(String[] args) {
		logger.info("*** in main");
		logger.info(run(new KubernetestAdapter()));
	}
	
	public static String run(TestAdapter theAdapter) {
		
		adapter = theAdapter;
		
		try {
			FastClasspathScanner scanner = new FastClasspathScanner(new String[] {"org.art.db.tests"} );
			
			scanner.scan();

			List<String> classNames = scanner.getSubclassesOf(BaseIntegrationTest.class);

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
			
			return builder.toString();
		} catch (Exception exc) {
			logger.info("Exception in main " + exc.getMessage());
			exc.printStackTrace();
			return exc.getMessage();
		}
	}
	
	public static TestAdapter getAdapter() {
		return adapter;
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

			StringBuilder builder = new StringBuilder();
			
			builder.append("\n\n\n\n\nStarting test: " + clazz.getSimpleName());
			builder.append("\n\n\n");
			
			logger.info(builder.toString());
			
			for (Method test : tests) {
				totalTests++;
				
				try {
					if (beforeEach != null) {
						beforeEach.invoke(instance);
					}
					
					test.invoke(instance);
					
	
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
				} finally {
					if (afterEach != null) {
						try {
							afterEach.invoke(instance);
						} catch (Exception exc) {
							logger.info("exception in afterEach " + exc.getMessage());
						}
					}
					
					builder.append("\n\n\n\n\nEnd      test: " + clazz.getSimpleName());
					builder.append("\nTotal tests:   " + totalTests + " Total Errors: " + totalErrors);
					builder.append("\n\n\n");
					logger.info(builder.toString());
					
				}
			}			
		} catch (Throwable thr1) {
			thr1.printStackTrace();
		}
		
	}

}
