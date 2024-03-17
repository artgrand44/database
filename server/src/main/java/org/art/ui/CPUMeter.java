package org.art.ui;

import static java.util.Objects.requireNonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;


public class CPUMeter {
	
	private final OperatingSystemMXBean operatingSystemBean;

	private final Class<?> operatingSystemBeanClass;

	private final Method systemCpuUsage;

	private static final List<String> OPERATING_SYSTEM_BEAN_CLASS_NAMES = Arrays.asList(
			"com.ibm.lang.management.OperatingSystemMXBean", 
			"com.sun.management.OperatingSystemMXBean" 
	);

	CPUMeter() {
		this.operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
		this.operatingSystemBeanClass = getFirstClassFound(OPERATING_SYSTEM_BEAN_CLASS_NAMES);
		Method getCpuLoad = detectMethod("getCpuLoad");
		this.systemCpuUsage = getCpuLoad != null ? getCpuLoad : detectMethod("getSystemCpuLoad");
	}
	
	private Method detectMethod(String name) {
		requireNonNull(name);
		if (operatingSystemBeanClass == null) {
			return null;
		}
		try {
			// ensure the Bean we have is actually an instance of the interface
			operatingSystemBeanClass.cast(operatingSystemBean);
			return operatingSystemBeanClass.getMethod(name);
		}
		catch (ClassCastException | NoSuchMethodException | SecurityException e) {
			return null;
		}
	}

	private Class<?> getFirstClassFound(List<String> classNames) {
		for (String className : classNames) {
			try {
				return Class.forName(className);
			}
			catch (ClassNotFoundException ignore) {
			}
		}
		return null;
	}
	
	private double invoke(Method method) {
		try {
			return method != null ? (double) method.invoke(operatingSystemBean) : Double.NaN;
		}
		catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
			return Double.NaN;
		}
	}

	double getCPU() {
		return  invoke(systemCpuUsage);
	}
}
