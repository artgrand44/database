package org.art.db.client.dao;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention (RUNTIME)
@Target(TYPE)
@Repeatable(Subclasses.class)
public @interface Subclass {
	String value();
	Class<?> subclass();
}
