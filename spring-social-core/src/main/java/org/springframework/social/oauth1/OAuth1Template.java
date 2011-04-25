/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.social.oauth1;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.social.support.ClientHttpRequestFactorySelector;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

/**
 * OAuth10Operations implementation that uses REST-template to make the OAuth calls.
 * @author Keith Donald
 */
public class OAuth1Template implements OAuth1Operations {

	private final String consumerKey;

	private final String consumerSecret;

	private final URI requestTokenUrl;

	private final String authenticateUrl;
	
	private final String authorizeUrl;

	private final URI accessTokenUrl;

	private final RestTemplate restTemplate;

	private final OAuth1Version version;
	
	private final SigningSupport signingUtils;

	/**
	 * Constructs an OAuth1Template.
	 * @param version the version of OAuth 1, either 10 or 10a.
	 */
	public OAuth1Template(String consumerKey, String consumerSecret, String requestTokenUrl, String authorizeUrl, String authenticateUrl, String accessTokenUrl, OAuth1Version version) {
		this.consumerKey = consumerKey;
		this.consumerSecret = consumerSecret;
		this.requestTokenUrl = encodeTokenUri(requestTokenUrl);
		this.authorizeUrl = authorizeUrl;
		this.authenticateUrl = authenticateUrl;
		this.accessTokenUrl = encodeTokenUri(accessTokenUrl);
		this.version = version;
		this.restTemplate = createRestTemplate();
		this.signingUtils = new SigningSupport();
	}

	// implementing OAuth1Operations
	
	public OAuth1Version getVersion() {
		return version;
	}
	
	public OAuthToken fetchRequestToken(String callbackUrl, MultiValueMap<String, String> additionalParameters) {
		Map<String, String> oauthParameters = new HashMap<String, String>(1, 1);
		if (version == OAuth1Version.CORE_10_REVISION_A) {
			oauthParameters.put("oauth_callback", callbackUrl);
		}
		return exchangeForToken(requestTokenUrl, oauthParameters, additionalParameters, null);
	}

	public String buildAuthorizeUrl(String requestToken, OAuth1Parameters parameters) {
		return buildAuthUrl(authorizeUrl, requestToken, parameters);
	}
	
	public String buildAuthenticateUrl(String requestToken, OAuth1Parameters parameters) {
		return authenticateUrl != null ? buildAuthUrl(authenticateUrl, requestToken, parameters) : buildAuthorizeUrl(requestToken, parameters);
	}

	public OAuthToken exchangeForAccessToken(AuthorizedRequestToken requestToken, MultiValueMap<String, String> additionalParameters) {
		Map<String, String> tokenParameters = new HashMap<String, String>(2, 1);
		tokenParameters.put("oauth_token", requestToken.getValue());
		if (version == OAuth1Version.CORE_10_REVISION_A) {
			tokenParameters.put("oauth_verifier", requestToken.getVerifier());
		}
		return exchangeForToken(accessTokenUrl, tokenParameters, additionalParameters, requestToken.getSecret());
	}

	// subclassing hooks

	protected String getConsumerKey() {
		return consumerKey;
	}
	
	protected OAuthToken createAccessToken(String accessToken, String secret, MultiValueMap<String, String> body) {
		return new OAuthToken(accessToken, secret);
	}

	protected MultiValueMap<String, String> getCustomAuthorizationParameters() {
		return null;
	}
	
	// internal helpers

	private RestTemplate createRestTemplate() {
		RestTemplate restTemplate = new RestTemplate(ClientHttpRequestFactorySelector.getRequestFactory());
		List<HttpMessageConverter<?>> converters = new ArrayList<HttpMessageConverter<?>>(1);
		converters.add(new FormHttpMessageConverter() {
			public boolean canRead(Class<?> clazz, MediaType mediaType) {
				// always read MultiValueMaps as x-www-url-formencoded even if contentType not set properly by provider				
				return MultiValueMap.class.isAssignableFrom(clazz);
			}
		});
		restTemplate.setMessageConverters(converters);
		return restTemplate;
	}
	
	private URI encodeTokenUri(String url) {
		try {
			return new URI(UriUtils.encodeUri(url, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Not a valid url: " + url, e);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private OAuthToken exchangeForToken(URI tokenUrl, Map<String, String> tokenParameters, MultiValueMap<String, String> additionalParameters, String tokenSecret) {
		HttpHeaders headers = new HttpHeaders();
		headers.add("Authorization", buildAuthorizationHeaderValue(tokenUrl, tokenParameters, additionalParameters, tokenSecret));
		ResponseEntity<MultiValueMap> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, new HttpEntity<MultiValueMap<String, String>>(additionalParameters, headers), MultiValueMap.class);
		MultiValueMap<String, String> body = response.getBody();
		return createAccessToken(body.getFirst("oauth_token"), body.getFirst("oauth_token_secret"), body);
	}

	private String buildAuthorizationHeaderValue(URI tokenUrl, Map<String, String> tokenParameters, MultiValueMap<String, String> additionalParameters, String tokenSecret) {
		Map<String, String> oauthParameters = signingUtils.commonOAuthParameters(consumerKey);
		oauthParameters.putAll(tokenParameters);
		if (additionalParameters == null) {
			additionalParameters = EmptyMultiValueMap.instance();
		}
		return signingUtils.buildAuthorizationHeaderValue(HttpMethod.POST, tokenUrl, oauthParameters, additionalParameters, consumerSecret, tokenSecret);
	}

	private String buildAuthUrl(String baseAuthUrl, String requestToken, OAuth1Parameters parameters) {
		StringBuilder authUrl = new StringBuilder(baseAuthUrl).append('?').append("oauth_token").append('=').append(formEncode(requestToken));
		if (version == OAuth1Version.CORE_10) {
			authUrl.append('&').append("oauth_callback").append("=").append(formEncode(parameters.getCallbackUrl()));
		}
		MultiValueMap<String, String> additionalParameters = getAdditionalParameters(parameters.getAdditionalParameters());
		if (additionalParameters != null) {
			for (Iterator<Entry<String, List<String>>> additionalParams = parameters.getAdditionalParameters().entrySet().iterator(); additionalParams.hasNext();) {
				Entry<String, List<String>> param = additionalParams.next();
				String name = formEncode(param.getKey());
				for (Iterator<String> values = param.getValue().iterator(); values.hasNext();) {
					authUrl.append('&').append(name).append('=').append(formEncode(values.next()));
				}
			}
		}		
		return authUrl.toString();
	}

	private MultiValueMap<String, String> getAdditionalParameters(MultiValueMap<String, String> clientAdditionalParameters) {
		MultiValueMap<String, String> additionalParameters = getCustomAuthorizationParameters();
		if (additionalParameters == null) {
			return clientAdditionalParameters;
		} else {
			additionalParameters.putAll(clientAdditionalParameters);
			return additionalParameters;
		}
	}
	
	private String formEncode(String data) {
		try {
			return URLEncoder.encode(data, "UTF-8");
		}
		catch (UnsupportedEncodingException ex) {
			// should not happen, UTF-8 is always supported
			throw new IllegalStateException(ex);
		}
	}
	
	// testing hooks
	RestTemplate getRestTemplate() {
		return restTemplate;
	}

}