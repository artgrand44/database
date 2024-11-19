package org.art.db.common;

import java.io.File;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestClient {
	
	private static final Logger logger = LoggerFactory.getLogger(RestClient.class);
	
	private static final HttpClient client = HttpClient.newBuilder().build();
	
	private String user;
	private String password;
	private Duration timeout = DEFAULT_TIMEOUT;
	
	private final static String clientName = ApplicationContext.getInstance().getAppName();
	
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3000);
		
	public RestClient() {
		
	}
	
	public RestClient(Duration timeout) {
		this.timeout = timeout;
	}
	
	public RestClient(String user, String password) {
		this.user = user;
		this.password = password;
	}
	
	public RestClient(String user, String password, Duration timeout) {
		this.user = user;
		this.password = password;
		this.timeout = timeout;
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
			
			if (cause instanceof ConnectException) {
				throw new RestException(RestException.NO_CONNECTION, "", cause);
			} else if (cause instanceof SocketException) {
				throw new RestException(500, "Socket exception " + uri + " " + cause.getMessage(), cause);
			} else  if (cause instanceof RestException rest){
				throw rest;
			} else {
				throw new RestException(500, "Exception calling uri " + uri + " " + cause.getMessage(), cause);
			}
		} 
	}
	
	
	public Future<HttpResponse<byte[]>> callAsync(String method, URI uri, Object body)  { 
		HttpRequest.Builder builder = HttpRequest.newBuilder()	
				.uri(uri)
				.header("Content-Type", "application/json")
				.header("accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.header("Client", clientName)
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
	
	public List<byte[]> getAll(List<URI> urls, String path) throws Exception{
		List<byte[]> result = new ArrayList<>();
		List<Future<HttpResponse<byte[]>>> futures = new ArrayList<>();
		
		for (URI uri : urls) {
			futures.add(this.callAsync("GET", this.buildURI(uri, path), null));
		}
		
		for (int idx = 0; idx < futures.size(); idx++) {
			try {
				Future<HttpResponse<byte[]>> future = futures.get(idx);
				HttpResponse<byte[]> response = future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
				Optional<String> warning = response.headers().firstValue("Warning");
				int status = response.statusCode();

				if (status == RestException.TEA_POT) {
					String warningText = warning.orElse("");
					String message = warningText.substring("Warning: 199 - ".length()); 

					throw new DBRuntimeException(message);
				} else if (status != 200) {
					logger.info("network error " + status + " " + urls.get(idx));
					throw new RestException(status, "There was a network errror");
				}

				byte[] bytes =  response.body();
				HttpHeaders headers = response.headers();

				if ("gzip".equals(headers.firstValue("Content-Encoding").orElse(null))) {
					bytes = ZipUtils.decompress(bytes);
				} 

				result.add(bytes);

			} catch (Exception exc) {
				
			}
		}
		
		return result;
	}
	
	private String getAuthToken() {
					
		if (this.user == null || this.password == null) {
			return null;
		}
		
		try {
			return "Basic " + Base64.getEncoder().encodeToString((this.user + ":" + this.password).getBytes());
		} catch (Exception exc) {
			logger.error("There was an exception decrypting the password " + exc.getMessage());
			return "";
		}
	}
	
	public String getFile(URI uri, File file) throws Exception {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder()	
					.uri(uri)
					.GET()
					.header("Content-Type", "application/json")
					.header("accept", "application/json")
					.header("Accept-Encoding", "gzip")
					.header("Client", clientName)
					.timeout(this.timeout)
					.version(HttpClient.Version.HTTP_2);

			String token = this.getAuthToken();
			
			if (token != null) {
				builder = builder.header("Authorization", token);
			}


			HttpRequest request = builder.build();
			File tempFile = File.createTempFile("temp", ".zip", file.getParentFile());
			HttpResponse<Path> response = client.send(request, BodyHandlers.ofFile(tempFile.toPath()));
			int status = response.statusCode();

			if (status != 200) {
				logger.info("getfile network error " + status);
				throw new RestException(status, "There was a network errror");
			}
			
			String name = response.headers().firstValue("filename").orElse(null);
			
			if (name != null) {
				ZipUtils.decompress(tempFile, file);
				logger.info("zip " + tempFile.length() + " file " + file.length());
			}
			
			return  response.headers().firstValue("filename").orElse(null);
		} catch(Exception exc) {
			logger.error("getfile " + exc.getMessage());
			throw new RestException(500, exc.getMessage());
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
	
	public static void touch() {
		
	}
	
}