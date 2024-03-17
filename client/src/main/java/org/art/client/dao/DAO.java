package org.art.client.dao;

public interface DAO<T> {
	public void insert(T bean) throws Exception;
	public void delete(T bean) throws Exception;
	public void update(T bean, String ... fields) throws Exception;
	public Iterable<T> select(String statement, Object ... parameters) throws Exception;
}
