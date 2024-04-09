package org.art.db.common;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestClient {
	
	private static final Logger logger = LoggerFactory.getLogger(RestClient.class);
	
	private static final HttpClient client = HttpClient.newBuilder().build();
	
	private String user;
	
	private String password;
	
	private Duration timeout = Duration.ofSeconds(3000);
	
	private final String localHost;
	
	public RestClient() {
		try {
			this.localHost = InetAddress.getLocalHost().getHostAddress();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}		
	}
	
	public RestClient(String user, String password) {
		this.user = user;
		this.password = password;
		
		try {
			this.localHost = InetAddress.getLocalHost().getHostAddress();
		} catch (Exception exc) {
			throw new IllegalStateException(exc);
		}
	}
	
	public  void broadcast(Collection<URI> baseUrls, String path,byte[] body) throws Exception {
		List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
		
		for (URI base : baseUrls) {
			futures.add(callAsync("PUT", buildURI(base, path), body));
		}
			
		Exception exception = null;
		
		for (Future<HttpResponse<byte[]>> future : futures) {
			HttpResponse<byte[]> response = future.get();
			
			if (response.statusCode() != 200) {
				Optional<String> warning = response.headers().firstValue("Warning");
				String warningText = warning.orElse("");
				
				exception = new RestException(200, warningText);
			}
		}
			
		if (exception != null) {
			throw exception;
		}
	}
			
	public  URI buildURI(URI base, String fileTemplate, Object ... args) throws Exception {
		return args.length == 0 ? base.resolve(fileTemplate) : base.resolve(String.format(fileTemplate, args));
	}
	
	public  byte[] call(String method, URI uri, Object body) throws Exception { 
		try {			
			HttpResponse<byte[]> response = callAsync(method, uri, body).get();
			Optional<String> warning = response.headers().firstValue("Warning");
			int status = response.statusCode();
			
			if (status == RestException.TEA_POT) {
				String warningText = warning.orElse("");
				String message = warningText.substring("Warning: 199 - ".length()); 
				
				throw new DBRuntimeException(message);
			} else if (status != 200) {
				logger.info("network error " + status + " " + uri);
				throw new RestException(status, "There was a network errror");
			}
			
			byte[] result =  response.body();
			HttpHeaders headers = response.headers();
			
			if ("gzip".equals(headers.firstValue("Content-Encoding").orElse(null))) {
				result = ZipUtils.decompress(result);
			}
			
			return result;
		} catch (ExecutionException exc) {
			Throwable cause = exc.getCause();
			
			logger.info("Exception for " + uri + " " + cause);
			if (cause instanceof ConnectException) {
				throw new RestException(RestException.NO_CONNECTION, "", cause);
			} else  if (cause instanceof RestException rest){
				throw rest;
			} else {
				throw new RestException(500, "Exception calling uri " + uri + " " + cause.getMessage(), cause);
			}
		} 
	}
	
	
	private Future<HttpResponse<byte[]>> callAsync(String method, URI uri, Object body) throws Exception { 
		HttpRequest.Builder builder = HttpRequest.newBuilder()	
				.uri(uri)
				.header("Content-Type", "application/json")
				.header("accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.header("Client", this.localHost)
				.timeout(this.timeout)
				.version(HttpClient.Version.HTTP_2);
		
		String token = this.getAuthToken();
		
		if (token != null) {
			builder = builder.header("Authorization", token);
		}
		
		BodyPublisher publisher;

		if (body == null) {
			publisher = BodyPublishers.noBody();
		} else {
			byte[] bytes = (byte[])body; 
			
			publisher = BodyPublishers.ofByteArray(ZipUtils.compress(bytes));
			builder = builder.header("Content-Encoding", "gzip");
		}

		HttpRequest request = builder.method(method.toUpperCase(), publisher).build();

		return client.sendAsync(request, BodyHandlers.ofByteArray());
	}
	
	public  byte[] get(URI uri) throws Exception {
		return this.call("GET", uri, null);
	}
	
	
	private String getAuthToken() {
					
		if (this.user == null || this.password == null) {
			return null;
		}
		
		try {
			return "Basic " + Base64.getEncoder().encodeToString((this.user + ":" + this.password).getBytes());
		} catch (Exception exc) {
			logger.info("There was an exception decrypting the password " + exc.getMessage());
			return "";
		}
	}
	@PostConstruct
	public void initialize() {
	
	}
	
	public  byte[] post(URI uri, Object body) throws Exception { 
		return this.call("POST", uri, body);
	}
	
		
	public  byte[] put(URI uri, Object obj) throws Exception {
		return this.call("PUT", uri, obj);
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}
	
}